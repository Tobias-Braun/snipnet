import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import Type from 'typebox';

import { AppError } from '../errors.js';
import { Court, Job, Video } from '../schemas.js';
import { toJob } from '../serialize.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Postgres error code for a violated unique constraint. */
const UNIQUE_VIOLATION = '23505';

const IdParams = Type.Object({ id: Type.String() });

/** Starting an analysis and polling its job (`/v1/videos/:id/analyze`, `/v1/jobs/:id`). */
export const jobRoutes: FastifyPluginCallbackTypebox = (app, _options, done) => {
  app.addSchema(Job);
  app.addSchema(Court);
  app.addSchema(Video);

  const security = [{ bearerAuth: [] }];
  const error = Type.Ref('ErrorResponse');

  app.post(
    '/videos/:id/analyze',
    {
      preHandler: app.authenticate,
      schema: {
        tags: ['jobs'],
        security,
        params: IdParams,
        body: Type.Object({}, { additionalProperties: false }),
        response: { 202: Type.Ref('Job'), 400: error, 401: error, 404: error, 409: error },
      },
    },
    async (request, reply) => {
      const notFound = new AppError('not_found', 'Video not found');
      if (!UUID_PATTERN.test(request.params.id)) throw notFound;

      try {
        const job = await app.db.transaction().execute(async (trx) => {
          // The row lock serializes this against a concurrent delete or a second analyze of the same video.
          const video = await trx
            .selectFrom('videos')
            .selectAll()
            .where('id', '=', request.params.id)
            .where('user_id', '=', request.user.sub)
            .forUpdate()
            .executeTakeFirst();
          if (video === undefined) throw notFound;

          if (video.status === 'created') {
            throw new AppError('conflict', 'The proxy has not been uploaded yet');
          }
          if (video.court === null) {
            throw new AppError('conflict', 'The court has to be marked before the video can be analyzed');
          }
          const active = await trx
            .selectFrom('jobs')
            .select('id')
            .where('video_id', '=', video.id)
            .where('status', 'in', ['queued', 'running'])
            .executeTakeFirst();
          if (active !== undefined) throw new AppError('conflict', 'An analysis is already in progress');

          // An analyzed or failed video may be analyzed again (a retry after failure, or a newer model); its
          // earlier segment sets and jobs stay as history.
          await trx
            .updateTable('videos')
            .set({ status: 'analyzing', updated_at: new Date() })
            .where('id', '=', video.id)
            .execute();
          return trx
            .insertInto('jobs')
            .values({ video_id: video.id })
            .returningAll()
            .executeTakeFirstOrThrow();
        });
        return await reply.code(202).send(toJob(job));
      } catch (caught) {
        // The partial unique index on active jobs is the backstop for the check above under races.
        if (isUniqueViolation(caught)) throw new AppError('conflict', 'An analysis is already in progress');
        throw caught;
      }
    },
  );

  app.get(
    '/jobs/:id',
    {
      preHandler: app.authenticate,
      schema: {
        tags: ['jobs'],
        security,
        params: IdParams,
        response: { 200: Type.Ref('Job'), 401: error, 404: error },
      },
    },
    async (request) => {
      if (!UUID_PATTERN.test(request.params.id)) throw new AppError('not_found', 'Job not found');
      // Joining through the video restricts the lookup to the caller's own jobs; others look like missing ones.
      const job = await app.db
        .selectFrom('jobs')
        .innerJoin('videos', 'videos.id', 'jobs.video_id')
        .selectAll('jobs')
        .where('jobs.id', '=', request.params.id)
        .where('videos.user_id', '=', request.user.sub)
        .executeTakeFirst();
      if (job === undefined) throw new AppError('not_found', 'Job not found');
      return toJob(job);
    },
  );

  done();
};

function isUniqueViolation(caught: unknown): boolean {
  return (
    typeof caught === 'object' && caught !== null && 'code' in caught && caught.code === UNIQUE_VIOLATION
  );
}
