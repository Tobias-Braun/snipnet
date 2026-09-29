import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import { sql, type ExpressionBuilder, type Selectable, type Transaction } from 'kysely';
import Type from 'typebox';

import type { CourtDetectionTasksTable, Database, JobsTable, VideosTable } from '../db/types.js';
import { AppError } from '../errors.js';
import { requireInternalToken } from '../plugins/internal-auth.js';
import { Court, CourtSuggestion, CourtSuggestionBody, Job, Video } from '../schemas.js';
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

/** Tolerance for floating point error when checking that a ROI lies inside the frame. */
const EPSILON = 1e-9;

const JobParams = Type.Object({ id: Type.String() });

/** Identifies the worker that claimed the job; reports from any other worker are rejected. */
const WorkerId = Type.String({ minLength: 1, maxLength: 200 });

/**
 * The `attempts` value of the job or court detection task as returned by claim. Unlike the worker id it is unique per claim, so a stale report
 * from an earlier attempt is detected even if the same worker id claimed the job again.
 */
const Attempt = Type.Integer({ minimum: 1 });

/** Outcome of a court detection: the suggestion, or `null` when the detector found no net. */
const CourtSuggestionReport = Type.Object(
  {
    workerId: WorkerId,
    attempt: Attempt,
    suggestion: Type.Union([CourtSuggestionBody, Type.Null()]),
  },
  { additionalProperties: false },
);

const Unit = Type.Number({ minimum: 0, maximum: 1 });

const ResultBody = Type.Object(
  {
    workerId: WorkerId,
    attempt: Attempt,
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

/** Failure report of a job or a court detection task: names the worker and the attempt, see `Attempt`. */
const FailBody = Type.Object(
  {
    workerId: WorkerId,
    attempt: Attempt,
    error: Type.String({ maxLength: 4000 }),
    retryable: Type.Boolean(),
  },
  { additionalProperties: false },
);

type Tx = Transaction<Database>;

/**
 * Rows a worker may claim, shared by jobs and court detection tasks: queued ones, and running ones whose worker
 * stopped reporting so that their lease expired.
 */
function isClaimable<T extends 'jobs' | 'court_detection_tasks'>(eb: ExpressionBuilder<Database, T>) {
  const row = eb as unknown as ExpressionBuilder<Database, 'jobs'>;
  return row.or([
    row('status', '=', 'queued'),
    row.and([row('status', '=', 'running'), row('lease_expires_at', '<', sql<Date>`now()`)]),
  ]);
}

/** Worker endpoints under `/internal/jobs/*`, guarded by `INTERNAL_TOKEN` instead of a user token. */
export const internalRoutes: FastifyPluginCallbackTypebox<{ internalToken: string }> = (
  app,
  options,
  done,
) => {
  app.addSchema(Job);
  app.addSchema(Court);
  app.addSchema(CourtSuggestion);
  app.addSchema(Video);

  // `onRequest` runs before body parsing and validation, so unauthenticated callers learn nothing about the schema.
  app.addHook('onRequest', requireInternalToken(options.internalToken));

  const security = [{ internalToken: [] }];
  const error = Type.Ref('ErrorResponse');

  const failResponse = { 204: Type.Null(), 400: error, 401: error, 404: error, 409: error };

  /** Route schema of the failure report of a job or a court detection task. */
  const failSchema = {
    tags: ['internal'],
    security,
    params: JobParams,
    body: FailBody,
    response: failResponse,
  };

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
   * Locks the job row for the rest of the transaction and requires it to be running and held by `workerId` in the
   * claim that returned `attempt`, so progress, result and failure reports serialize against each other and against a
   * re-claim. A worker whose lease expired and whose job was claimed again, by another worker or under the same
   * worker id, is answered with 409 and must drop the job.
   */
  async function lockRunningJob(
    trx: Tx,
    id: string,
    workerId: string,
    attempt: number,
  ): Promise<Selectable<JobsTable>> {
    if (!UUID_PATTERN.test(id)) throw new AppError('not_found', 'Job not found');
    const job = await trx.selectFrom('jobs').selectAll().where('id', '=', id).forUpdate().executeTakeFirst();
    if (job === undefined) throw new AppError('not_found', 'Job not found');
    if (job.status !== 'running') throw new AppError('conflict', `Job is ${job.status}, not running`);
    if (job.worker_id !== workerId || job.attempts !== attempt) {
      throw new AppError('conflict', 'Job is held by another worker or attempt, its lease was taken over');
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
          .where(isClaimable)
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
        body: Type.Object(
          { workerId: WorkerId, attempt: Attempt, progress: Unit },
          { additionalProperties: false },
        ),
        response: { 204: Type.Null(), 400: error, 401: error, 404: error, 409: error },
      },
    },
    async (request, reply) => {
      await app.db.transaction().execute(async (trx) => {
        const job = await lockRunningJob(trx, request.params.id, request.body.workerId, request.body.attempt);
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
      const proxyChanged = await app.db.transaction().execute(async (trx) => {
        const job = await lockRunningJob(trx, request.params.id, request.body.workerId, request.body.attempt);
        const video = await trx
          .selectFrom('videos')
          .selectAll()
          .where('id', '=', job.video_id)
          .forUpdate()
          .executeTakeFirstOrThrow();
        assertValidSegments(segments, video.duration_ms);

        // The presigned upload URL outlives the claim, so the proxy may have been replaced while the worker
        // analysed it. The prediction then describes a file that no longer exists and must not be stored. The
        // failure is committed, so the outcome is returned instead of thrown, which would roll it back.
        if (!(await proxyIsUnchanged(video))) {
          await failPermanently(trx, job, PROXY_CHANGED_MESSAGE);
          return true;
        }

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
        return false;
      });
      if (proxyChanged) throw new AppError('conflict', PROXY_CHANGED_MESSAGE);
      return reply.code(204).send(null);
    },
  );

  app.post('/jobs/:id/fail', { schema: failSchema }, async (request, reply) => {
    const { error: message, retryable } = request.body;
    await app.db.transaction().execute(async (trx) => {
      const job = await lockRunningJob(trx, request.params.id, request.body.workerId, request.body.attempt);
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
  });

  /**
   * Picks the oldest queued detection task, or a running one whose lease expired, and leases it to `workerId`. A
   * task that used up its attempts is failed instead. The suggestion just stays null then; the user can still
   * mark the court by hand, so this never blocks the video.
   */
  async function claimCourtTask(
    workerId: string,
  ): Promise<{ video: Selectable<VideosTable>; attempt: number } | null> {
    return app.db.transaction().execute(async (trx) => {
      for (;;) {
        const candidate = await trx
          .selectFrom('court_detection_tasks')
          .selectAll()
          .where(isClaimable)
          .orderBy('created_at')
          .orderBy('video_id')
          .limit(1)
          .forUpdate()
          .skipLocked()
          .executeTakeFirst();
        if (candidate === undefined) return null;

        if (candidate.attempts >= MAX_ATTEMPTS) {
          await trx
            .updateTable('court_detection_tasks')
            .set({
              status: 'failed',
              error: `Gave up after ${String(MAX_ATTEMPTS)} attempts without a result`,
              lease_expires_at: null,
            })
            .where('video_id', '=', candidate.video_id)
            .execute();
          continue;
        }

        const video = await trx
          .selectFrom('videos')
          .selectAll()
          .where('id', '=', candidate.video_id)
          .executeTakeFirstOrThrow();
        // The presigned upload URL outlives upload-complete, so the proxy may have been replaced since. The
        // suggestion would then describe another file; the task fails for good and the suggestion stays null.
        if (!(await proxyIsUnchanged(video))) {
          await trx
            .updateTable('court_detection_tasks')
            .set({ status: 'failed', error: PROXY_CHANGED_MESSAGE, lease_expires_at: null })
            .where('video_id', '=', candidate.video_id)
            .execute();
          continue;
        }

        await trx
          .updateTable('court_detection_tasks')
          .set({
            status: 'running',
            attempts: candidate.attempts + 1,
            worker_id: workerId,
            error: null,
            lease_expires_at: sql<Date>`now() + make_interval(mins => ${LEASE_MINUTES})`,
          })
          .where('video_id', '=', candidate.video_id)
          .execute();
        return { video, attempt: candidate.attempts + 1 };
      }
    });
  }

  /**
   * Locks the video's detection task and requires it to be running and still held by the reporting claim, so a late
   * report cannot resurrect a task or finish and fail the attempt of the worker that took it over. The attempt makes
   * the check exact even when the same worker id claimed the task again.
   */
  async function lockRunningCourtTask(
    trx: Tx,
    videoId: string,
    workerId: string,
    attempt: number,
  ): Promise<Selectable<CourtDetectionTasksTable>> {
    if (!UUID_PATTERN.test(videoId)) throw new AppError('not_found', 'Video not found');
    const task = await trx
      .selectFrom('court_detection_tasks')
      .selectAll()
      .where('video_id', '=', videoId)
      .forUpdate()
      .executeTakeFirst();
    if (task === undefined) throw new AppError('not_found', 'Court detection task not found');
    if (task.status !== 'running') {
      throw new AppError('conflict', `Court detection task is ${task.status}, not running`);
    }
    if (task.worker_id !== workerId || task.attempts !== attempt) {
      throw new AppError('conflict', 'Court detection task was claimed again, its lease was taken over');
    }
    return task;
  }

  app.post(
    '/court-detection/claim',
    {
      schema: {
        tags: ['internal'],
        security,
        body: Type.Object({ workerId: WorkerId }, { additionalProperties: false }),
        response: {
          200: Type.Object({
            videoId: Type.String({ format: 'uuid' }),
            attempt: Attempt,
            proxyUrl: Type.String(),
          }),
          204: Type.Null(),
          400: error,
          401: error,
        },
      },
    },
    async (request, reply) => {
      const claimed = await claimCourtTask(request.body.workerId);
      if (claimed === null) return reply.code(204).send(null);
      const { video, attempt } = claimed;
      return { videoId: video.id, attempt, proxyUrl: await app.storage.presignDownload(video.object_key) };
    },
  );

  app.post(
    '/videos/:id/court-suggestion',
    {
      schema: {
        tags: ['internal'],
        security,
        params: JobParams,
        // A `null` suggestion reports that the detection ran but found no net.
        body: CourtSuggestionReport,
        response: { 204: Type.Null(), 400: error, 401: error, 404: error, 409: error },
      },
    },
    async (request, reply) => {
      const { workerId, attempt, suggestion } = request.body;
      if (suggestion !== null) {
        const { roi } = suggestion.court;
        if (roi.x + roi.width > 1 + EPSILON || roi.y + roi.height > 1 + EPSILON) {
          throw new AppError('validation_error', 'roi must lie inside the frame');
        }
      }
      await app.db.transaction().execute(async (trx) => {
        await lockRunningCourtTask(trx, request.params.id, workerId, attempt);
        await trx
          .updateTable('videos')
          .set({
            court_suggestion: suggestion === null ? null : JSON.stringify(suggestion),
            updated_at: sql<Date>`now()`,
          })
          .where('id', '=', request.params.id)
          .execute();
        await trx
          .updateTable('court_detection_tasks')
          .set({ status: 'succeeded', error: null, lease_expires_at: null })
          .where('video_id', '=', request.params.id)
          .execute();
      });
      return reply.code(204).send(null);
    },
  );

  app.post('/videos/:id/court-suggestion/fail', { schema: failSchema }, async (request, reply) => {
    const { workerId, attempt, error: message, retryable } = request.body;
    await app.db.transaction().execute(async (trx) => {
      const task = await lockRunningCourtTask(trx, request.params.id, workerId, attempt);
      const requeue = retryable && task.attempts < MAX_ATTEMPTS;
      await trx
        .updateTable('court_detection_tasks')
        .set({
          status: requeue ? 'queued' : 'failed',
          error: message,
          worker_id: null,
          lease_expires_at: null,
        })
        .where('video_id', '=', request.params.id)
        .execute();
    });
    return reply.code(204).send(null);
  });

  done();
};
