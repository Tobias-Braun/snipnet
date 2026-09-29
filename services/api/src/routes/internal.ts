import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import { sql, type Selectable, type Transaction } from 'kysely';
import Type from 'typebox';

import type { Database, JobsTable, VideosTable } from '../db/types.js';
import { AppError } from '../errors.js';
import { requireInternalToken } from '../plugins/internal-auth.js';
import { Court, Job, Video } from '../schemas.js';
import { assertValidSegments, SegmentInput, serializeSegments } from '../segments.js';
import { toJob, toVideo } from '../serialize.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A job that has been claimed this many times without succeeding is not handed out again. */
export const MAX_ATTEMPTS = 3;

/** A worker has to report progress at least this often, otherwise its job may be claimed by another worker. */
export const LEASE_MINUTES = 10;

/** Scores of long videos are large: a 3 hour match at 15 Hz is around a million characters of JSON. */
const RESULT_BODY_LIMIT = 16 * 1024 * 1024;

const PROXY_CHANGED_MESSAGE = 'The proxy was changed or removed after the upload was confirmed';

const JobParams = Type.Object({ id: Type.String() });

/** Identifies the worker that claimed the job; reports from any other worker are rejected. */
const WorkerId = Type.String({ minLength: 1, maxLength: 200 });

const Unit = Type.Number({ minimum: 0, maximum: 1 });

const ResultBody = Type.Object(
  {
    workerId: WorkerId,
    modelVersion: Type.String({ minLength: 1, maxLength: 200 }),
    segments: Type.Array(SegmentInput),
    scores: Type.Union([
      Type.Object(
        { hz: Type.Number({ exclusiveMinimum: 0 }), values: Type.Array(Unit) },
        { additionalProperties: false },
      ),
      Type.Null(),
    ]),
  },
  { additionalProperties: false },
);

type Tx = Transaction<Database>;

/** Worker endpoints under `/internal/jobs/*`, guarded by `INTERNAL_TOKEN` instead of a user token. */
export const internalRoutes: FastifyPluginCallbackTypebox<{ internalToken: string }> = (
  app,
  options,
  done,
) => {
  app.addSchema(Job);
  app.addSchema(Court);
  app.addSchema(Video);

  // `onRequest` runs before body parsing and validation, so unauthenticated callers learn nothing about the schema.
  app.addHook('onRequest', requireInternalToken(options.internalToken));

  const security = [{ internalToken: [] }];
  const error = Type.Ref('ErrorResponse');

  /**
   * Marks a job failed for good and fails its video. Used when the retry budget is spent, either because the
   * worker reported a non-retryable error or because leases kept expiring.
   */
  async function failPermanently(trx: Tx, job: Selectable<JobsTable>, message: string): Promise<void> {
    await trx
      .updateTable('jobs')
      .set({ status: 'failed', error: message, finished_at: sql`now()`, lease_expires_at: null })
      .where('id', '=', job.id)
      .execute();
    await trx
      .updateTable('videos')
      .set({ status: 'failed', updated_at: sql`now()` })
      .where('id', '=', job.video_id)
      .execute();
  }

  /**
   * Locks the job row for the rest of the transaction and requires it to be running and held by `workerId`, so
   * progress, result and failure reports serialize against each other and against a claim by another worker. A
   * worker whose lease expired and was taken over by another worker is answered with 409 and must drop the job.
   */
  async function lockRunningJob(trx: Tx, id: string, workerId: string): Promise<Selectable<JobsTable>> {
    if (!UUID_PATTERN.test(id)) throw new AppError('not_found', 'Job not found');
    const job = await trx.selectFrom('jobs').selectAll().where('id', '=', id).forUpdate().executeTakeFirst();
    if (job === undefined) throw new AppError('not_found', 'Job not found');
    if (job.status !== 'running') throw new AppError('conflict', `Job is ${job.status}, not running`);
    if (job.worker_id !== workerId) {
      throw new AppError('conflict', 'Job is held by another worker, its lease was taken over');
    }
    return job;
  }

  /**
   * Picks the oldest claimable job with `FOR UPDATE SKIP LOCKED`, so parallel workers each lock a different row
   * instead of waiting for or double-claiming one. A running job whose lease has expired counts as claimable
   * (its worker died); if it has already used all attempts it is failed instead and the next candidate is tried.
   * A candidate whose proxy was replaced after `upload-complete` (the presigned PUT URL outlives it) or removed is
   * not what the client confirmed, so it fails for good as well. That check runs inside the transaction: when the
   * object store cannot be reached, the error rolls the claim back instead of leaving a running job whose attempt
   * is spent although no worker ever received it.
   */
  async function claimNext(
    workerId: string,
  ): Promise<{ job: Selectable<JobsTable>; video: Selectable<VideosTable> } | null> {
    return app.db.transaction().execute(async (trx) => {
      for (;;) {
        const candidate = await trx
          .selectFrom('jobs')
          .selectAll()
          .where((eb) =>
            eb.or([
              eb('status', '=', 'queued'),
              eb.and([eb('status', '=', 'running'), eb('lease_expires_at', '<', sql<Date>`now()`)]),
            ]),
          )
          .orderBy('created_at')
          .orderBy('id')
          .limit(1)
          .forUpdate()
          .skipLocked()
          .executeTakeFirst();
        if (candidate === undefined) return null;

        if (candidate.attempts >= MAX_ATTEMPTS) {
          await failPermanently(
            trx,
            candidate,
            `Gave up after ${String(MAX_ATTEMPTS)} attempts without a result`,
          );
          continue;
        }

        const video = await trx
          .selectFrom('videos')
          .selectAll()
          .where('id', '=', candidate.video_id)
          .executeTakeFirstOrThrow();
        if (!(await proxyIsUnchanged(video))) {
          await failPermanently(trx, candidate, PROXY_CHANGED_MESSAGE);
          continue;
        }

        const job = await trx
          .updateTable('jobs')
          .set({
            status: 'running',
            attempts: candidate.attempts + 1,
            worker_id: workerId,
            progress: 0,
            error: null,
            lease_expires_at: sql<Date>`now() + make_interval(mins => ${LEASE_MINUTES})`,
            started_at: sql<Date>`now()`,
          })
          .where('id', '=', candidate.id)
          .returningAll()
          .executeTakeFirstOrThrow();
        return { job, video };
      }
    });
  }

  /** Whether the stored proxy still has the ETag recorded at upload-complete. Videos without one are not checked. */
  async function proxyIsUnchanged(video: Selectable<VideosTable>): Promise<boolean> {
    if (video.proxy_etag === null) return true;
    const object = await app.storage.objectInfo(video.object_key);
    return object?.etag === video.proxy_etag;
  }

  app.post(
    '/jobs/claim',
    {
      schema: {
        tags: ['internal'],
        security,
        body: Type.Object(
          { workerId: Type.String({ minLength: 1, maxLength: 200 }) },
          { additionalProperties: false },
        ),
        response: {
          200: Type.Object({ job: Type.Ref('Job'), video: Type.Ref('Video'), proxyUrl: Type.String() }),
          204: Type.Null(),
          400: error,
          401: error,
        },
      },
    },
    async (request, reply) => {
      const claimed = await claimNext(request.body.workerId);
      if (claimed === null) return reply.code(204).send(null);

      const proxyUrl = await app.storage.presignDownload(claimed.video.object_key);
      return { job: toJob(claimed.job), video: toVideo(claimed.video, claimed.job), proxyUrl };
    },
  );

  app.post(
    '/jobs/:id/progress',
    {
      schema: {
        tags: ['internal'],
        security,
        params: JobParams,
        body: Type.Object({ workerId: WorkerId, progress: Unit }, { additionalProperties: false }),
        response: { 204: Type.Null(), 400: error, 401: error, 404: error, 409: error },
      },
    },
    async (request, reply) => {
      await app.db.transaction().execute(async (trx) => {
        const job = await lockRunningJob(trx, request.params.id, request.body.workerId);
        await trx
          .updateTable('jobs')
          .set({
            // Progress never moves backwards, so a delayed report cannot undo a newer one.
            progress: Math.max(job.progress, request.body.progress),
            lease_expires_at: sql<Date>`now() + make_interval(mins => ${LEASE_MINUTES})`,
          })
          .where('id', '=', job.id)
          .execute();
      });
      return reply.code(204).send(null);
    },
  );

  app.post(
    '/jobs/:id/result',
    {
      bodyLimit: RESULT_BODY_LIMIT,
      schema: {
        tags: ['internal'],
        security,
        params: JobParams,
        body: ResultBody,
        response: { 204: Type.Null(), 400: error, 401: error, 404: error, 409: error },
      },
    },
    async (request, reply) => {
      const { modelVersion, segments, scores } = request.body;
      await app.db.transaction().execute(async (trx) => {
        const job = await lockRunningJob(trx, request.params.id, request.body.workerId);
        const video = await trx
          .selectFrom('videos')
          .selectAll()
          .where('id', '=', job.video_id)
          .forUpdate()
          .executeTakeFirstOrThrow();
        assertValidSegments(segments, video.duration_ms);

        await trx
          .insertInto('segment_sets')
          .values({
            video_id: video.id,
            kind: 'prediction',
            job_id: job.id,
            model_version: modelVersion,
            segments: serializeSegments(segments),
            scores: scores === null ? null : JSON.stringify(scores),
          })
          .execute();
        await trx
          .updateTable('jobs')
          .set({
            status: 'succeeded',
            progress: 1,
            model_version: modelVersion,
            error: null,
            finished_at: sql<Date>`now()`,
            lease_expires_at: null,
          })
          .where('id', '=', job.id)
          .execute();
        await trx
          .updateTable('videos')
          .set({ status: 'analyzed', updated_at: sql<Date>`now()` })
          .where('id', '=', video.id)
          .execute();
      });
      return reply.code(204).send(null);
    },
  );

  app.post(
    '/jobs/:id/fail',
    {
      schema: {
        tags: ['internal'],
        security,
        params: JobParams,
        body: Type.Object(
          { workerId: WorkerId, error: Type.String({ maxLength: 4000 }), retryable: Type.Boolean() },
          { additionalProperties: false },
        ),
        response: { 204: Type.Null(), 400: error, 401: error, 404: error, 409: error },
      },
    },
    async (request, reply) => {
      const { error: message, retryable } = request.body;
      await app.db.transaction().execute(async (trx) => {
        const job = await lockRunningJob(trx, request.params.id, request.body.workerId);
        if (retryable && job.attempts < MAX_ATTEMPTS) {
          // Back to the queue for another worker; the video stays `analyzing`.
          await trx
            .updateTable('jobs')
            .set({ status: 'queued', error: message, worker_id: null, lease_expires_at: null, progress: 0 })
            .where('id', '=', job.id)
            .execute();
        } else {
          await failPermanently(trx, job, message);
        }
      });
      return reply.code(204).send(null);
    },
  );

  done();
};
