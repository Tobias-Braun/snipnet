import { randomUUID } from 'node:crypto';

import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import type { Selectable } from 'kysely';
import Type from 'typebox';

import type { JobsTable, VideosTable } from '../db/types.js';
import { AppError } from '../errors.js';
import { proxyKey } from '../plugins/storage.js';
import { Court, CourtBody, CourtSuggestion, Job, Video } from '../schemas.js';
import { toVideo } from '../serialize.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Largest value of the `integer` columns for the duration and frame size. */
const INT4_MAX = 2_147_483_647;

/** Upper bound for a proxy upload; a 480p proxy of many hours stays far below it. */
const MAX_PROXY_BYTES = 5 * 1024 ** 3;

/**
 * Tolerance for the "ROI inside the frame" check, so that `x + width` of values like 0.1 + 0.9 that do not add
 * up exactly in floating point is not rejected.
 */
const EPSILON = 1e-9;

const VideoParams = Type.Object({ id: Type.String() });

const CreateVideoBody = Type.Object(
  {
    filename: Type.String({ minLength: 1, maxLength: 255 }),
    durationMs: Type.Integer({ minimum: 1, maximum: INT4_MAX }),
    width: Type.Integer({ minimum: 1, maximum: INT4_MAX }),
    height: Type.Integer({ minimum: 1, maximum: INT4_MAX }),
    fps: Type.Number({ exclusiveMinimum: 0, maximum: 1000 }),
    proxySizeBytes: Type.Integer({ minimum: 1, maximum: MAX_PROXY_BYTES }),
  },
  { additionalProperties: false },
);

const Upload = Type.Object({
  url: Type.String(),
  method: Type.Literal('PUT'),
  headers: Type.Record(Type.String(), Type.String()),
  expiresAt: Type.String({ format: 'date-time' }),
});

/** A malformed id can never belong to anyone, so it is answered like an unknown one instead of hitting Postgres. */
function parseVideoId(id: string): string {
  if (!UUID_PATTERN.test(id)) throw new AppError('not_found', 'Video not found');
  return id;
}

/** Videos, their proxy upload through a presigned URL and the court marking (`/v1/videos/*`). */
export const videoRoutes: FastifyPluginCallbackTypebox = (app, _options, done) => {
  app.addSchema(Job);
  app.addSchema(Court);
  app.addSchema(CourtSuggestion);
  app.addSchema(Video);

  const security = [{ bearerAuth: [] }];
  const errors = {
    400: Type.Ref('ErrorResponse'),
    401: Type.Ref('ErrorResponse'),
    404: Type.Ref('ErrorResponse'),
  };

  /**
   * Loads a video the user owns. Videos of other users are reported as missing rather than forbidden, so ids
   * of other accounts cannot be probed.
   */
  async function findOwnedVideo(userId: string, id: string): Promise<Selectable<VideosTable>> {
    const row = await app.db
      .selectFrom('videos')
      .selectAll()
      .where('id', '=', parseVideoId(id))
      .where('user_id', '=', userId)
      .executeTakeFirst();
    if (row === undefined) throw new AppError('not_found', 'Video not found');
    return row;
  }

  async function latestJob(videoId: string): Promise<Selectable<JobsTable> | undefined> {
    return app.db
      .selectFrom('jobs')
      .selectAll()
      .where('video_id', '=', videoId)
      .orderBy('created_at', 'desc')
      .limit(1)
      .executeTakeFirst();
  }

  async function presentVideo(row: Selectable<VideosTable>) {
    return toVideo(row, await latestJob(row.id));
  }

  app.post(
    '/videos',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['videos'],
        security,
        body: CreateVideoBody,
        response: {
          201: Type.Object({ video: Type.Ref('Video'), upload: Upload }),
          400: errors[400],
          401: errors[401],
        },
      },
    },
    async (request, reply) => {
      const userId = request.user.sub;
      const { filename, durationMs, width, height, fps, proxySizeBytes } = request.body;

      // The id is chosen here because it is part of the object key that is stored with the row.
      const id = randomUUID();
      const objectKey = proxyKey(userId, id);
      const upload = await app.storage.presignUpload(objectKey, proxySizeBytes);
      const row = await app.db
        .insertInto('videos')
        .values({
          id,
          user_id: userId,
          filename,
          duration_ms: durationMs,
          width,
          height,
          fps,
          proxy_size_bytes: proxySizeBytes,
          object_key: objectKey,
        })
        .returningAll()
        .executeTakeFirstOrThrow();

      void reply.code(201);
      return {
        video: toVideo(row, undefined),
        upload: { ...upload, expiresAt: upload.expiresAt.toISOString() },
      };
    },
  );

  app.get(
    '/videos',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['videos'],
        security,
        response: {
          200: Type.Object({ items: Type.Array(Type.Ref('Video')) }),
          401: errors[401],
        },
      },
    },
    async (request) => {
      const rows = await app.db
        .selectFrom('videos')
        .selectAll()
        .where('user_id', '=', request.user.sub)
        .orderBy('created_at', 'desc')
        .orderBy('id')
        .execute();
      if (rows.length === 0) return { items: [] };

      // One query for the newest job of every video instead of one per video.
      const jobs = await app.db
        .selectFrom('jobs')
        .selectAll()
        .distinctOn('video_id')
        .where(
          'video_id',
          'in',
          rows.map((row) => row.id),
        )
        .orderBy('video_id')
        .orderBy('created_at', 'desc')
        .execute();
      const jobByVideo = new Map(jobs.map((job) => [job.video_id, job]));
      return { items: rows.map((row) => toVideo(row, jobByVideo.get(row.id))) };
    },
  );

  app.get(
    '/videos/:id',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['videos'],
        security,
        params: VideoParams,
        response: { 200: Type.Ref('Video'), 401: errors[401], 404: errors[404] },
      },
    },
    async (request) => presentVideo(await findOwnedVideo(request.user.sub, request.params.id)),
  );

  app.delete(
    '/videos/:id',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['videos'],
        security,
        params: VideoParams,
        response: { 204: Type.Null(), 401: errors[401], 404: errors[404], 409: Type.Ref('ErrorResponse') },
      },
    },
    async (request, reply) => {
      const id = parseVideoId(request.params.id);
      await app.db.transaction().execute(async (trx) => {
        // The row lock serializes this against a concurrent analyze of the same video, so no job can be
        // enqueued between the check below and the delete.
        const video = await trx
          .selectFrom('videos')
          .selectAll()
          .where('id', '=', id)
          .where('user_id', '=', request.user.sub)
          .forUpdate()
          .executeTakeFirst();
        if (video === undefined) throw new AppError('not_found', 'Video not found');

        // A still-queued job has not reached a worker, so it is dropped together with the video instead of
        // blocking the delete until some worker claims it (with no worker running that would be forever). The
        // rows are taken with SKIP LOCKED: a queued job that a worker's claim currently holds is left alone
        // and, being still active, turns into the 409 below. Waiting for that lock instead could deadlock with
        // a worker that holds a job and wants the video row we lock here.
        const queued = await trx
          .selectFrom('jobs')
          .select('id')
          .where('video_id', '=', video.id)
          .where('status', '=', 'queued')
          .forUpdate()
          .skipLocked()
          .execute();
        if (queued.length > 0) {
          await trx
            .deleteFrom('jobs')
            .where(
              'id',
              'in',
              queued.map((job) => job.id),
            )
            .execute();
        }

        // A worker may be downloading the proxy or about to post its result; deleting now would pull the data
        // out from under it. The client retries once the job has finished or failed. Throwing rolls back the
        // deletion of the queued jobs above.
        const active = await trx
          .selectFrom('jobs')
          .select('id')
          .where('video_id', '=', video.id)
          .where('status', 'in', ['queued', 'running'])
          .executeTakeFirst();
        if (active !== undefined) {
          throw new AppError('conflict', 'The video is being analyzed and cannot be deleted right now');
        }

        // The object goes first: if removing it fails the row stays and the client can retry, whereas the other
        // order would leave an object behind that nothing references anymore. Deleting a missing key succeeds.
        await app.storage.deleteObject(video.object_key);
        // Jobs and segment sets follow through their ON DELETE CASCADE foreign keys.
        await trx.deleteFrom('videos').where('id', '=', video.id).execute();
      });
      return reply.code(204).send(null);
    },
  );

  app.post(
    '/videos/:id/upload-complete',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['videos'],
        security,
        params: VideoParams,
        response: {
          200: Type.Ref('Video'),
          401: errors[401],
          404: errors[404],
          409: Type.Ref('ErrorResponse'),
        },
      },
    },
    async (request) => {
      const video = await findOwnedVideo(request.user.sub, request.params.id);

      const object = await app.storage.objectInfo(video.object_key);
      if (object === null) throw new AppError('conflict', 'The proxy has not been uploaded');
      if (object.sizeBytes !== video.proxy_size_bytes) {
        throw new AppError(
          'conflict',
          `The uploaded proxy has ${String(object.sizeBytes)} bytes but ${String(video.proxy_size_bytes)} were announced`,
        );
      }

      // Only a freshly created video moves to `uploaded`; a repeated call (or one after analysis started) is a
      // no-op that must not throw the video back in its lifecycle. The ETag is pinned by that first call only:
      // the presigned upload URL stays valid for an hour, so a later overwrite has to be detected by the worker
      // claim against this value instead of being adopted by a repeated call.
      // The court detection task is enqueued in the same transaction, so exactly the call that confirms the upload
      // creates it and a repeated call cannot queue a second one.
      const updated = await app.db.transaction().execute(async (trx) => {
        const row = await trx
          .updateTable('videos')
          .set({ status: 'uploaded', proxy_etag: object.etag, updated_at: new Date() })
          .where('id', '=', video.id)
          .where('status', '=', 'created')
          .returningAll()
          .executeTakeFirst();
        if (row !== undefined) {
          await trx
            .insertInto('court_detection_tasks')
            .values({ video_id: row.id })
            .onConflict((oc) => oc.column('video_id').doNothing())
            .execute();
        }
        return row;
      });
      return presentVideo(updated ?? (await findOwnedVideo(request.user.sub, video.id)));
    },
  );

  app.put(
    '/videos/:id/court',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['videos'],
        security,
        params: VideoParams,
        body: CourtBody,
        response: { 200: Type.Ref('Video'), ...errors },
      },
    },
    async (request) => {
      const { roi, netPoint } = request.body;
      // Range checks per coordinate come from the schema; what is left is the relation between values.
      if (roi.x + roi.width > 1 + EPSILON) {
        throw new AppError('validation_error', 'roi must lie inside the frame: x + width must be at most 1');
      }
      if (roi.y + roi.height > 1 + EPSILON) {
        throw new AppError('validation_error', 'roi must lie inside the frame: y + height must be at most 1');
      }

      const video = await findOwnedVideo(request.user.sub, request.params.id);
      const updated = await app.db
        .updateTable('videos')
        .set({ court: JSON.stringify({ roi, netPoint }), updated_at: new Date() })
        .where('id', '=', video.id)
        .returningAll()
        .executeTakeFirstOrThrow();
      return presentVideo(updated);
    },
  );

  done();
};
