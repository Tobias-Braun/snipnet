import { randomUUID } from 'node:crypto';

import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import type { Selectable } from 'kysely';
import Type from 'typebox';

import type { JobsTable, VideosTable } from '../db/types.js';
import { AppError } from '../errors.js';
import { proxyKey } from '../plugins/storage.js';
import { Court, CourtBody, Job, Video } from '../schemas.js';
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
      preHandler: app.authenticate,
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
      preHandler: app.authenticate,
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
      preHandler: app.authenticate,
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
      preHandler: app.authenticate,
      schema: {
        tags: ['videos'],
        security,
        params: VideoParams,
        response: { 204: Type.Null(), 401: errors[401], 404: errors[404] },
      },
    },
    async (request, reply) => {
      const video = await findOwnedVideo(request.user.sub, request.params.id);
      // The object goes first: if removing it fails the row stays and the client can retry, whereas the other
      // order would leave an object behind that nothing references anymore. Deleting a missing key succeeds.
      await app.storage.deleteObject(video.object_key);
      // Jobs and segment sets follow through their ON DELETE CASCADE foreign keys.
      await app.db.deleteFrom('videos').where('id', '=', video.id).execute();
      return reply.code(204).send(null);
    },
  );

  app.post(
    '/videos/:id/upload-complete',
    {
      preHandler: app.authenticate,
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

      const size = await app.storage.objectSize(video.object_key);
      if (size === null) throw new AppError('conflict', 'The proxy has not been uploaded');
      if (size !== video.proxy_size_bytes) {
        throw new AppError(
          'conflict',
          `The uploaded proxy has ${String(size)} bytes but ${String(video.proxy_size_bytes)} were announced`,
        );
      }

      // Only a freshly created video moves to `uploaded`; a repeated call (or one after analysis started) is a
      // no-op that must not throw the video back in its lifecycle.
      const updated = await app.db
        .updateTable('videos')
        .set({ status: 'uploaded', updated_at: new Date() })
        .where('id', '=', video.id)
        .where('status', '=', 'created')
        .returningAll()
        .executeTakeFirst();
      return presentVideo(updated ?? (await findOwnedVideo(request.user.sub, video.id)));
    },
  );

  app.put(
    '/videos/:id/court',
    {
      preHandler: app.authenticate,
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
