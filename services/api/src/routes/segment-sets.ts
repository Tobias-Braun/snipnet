import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import Type from 'typebox';

import { AppError } from '../errors.js';
import { SegmentSet } from '../schemas.js';
import { assertValidSegments, normalizeSegments, SegmentInput, serializeSegments } from '../segments.js';
import { toSegmentSet } from '../serialize.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A long editing session logs every operation with full before/after snapshots, so bodies can get large. */
const USER_SET_BODY_LIMIT = 5 * 1024 * 1024;

const IdParams = Type.Object({ id: Type.String() });

const EditOp = Type.Object(
  {
    op: Type.Union([
      Type.Literal('trim'),
      Type.Literal('split'),
      Type.Literal('merge'),
      Type.Literal('delete'),
      Type.Literal('add'),
      Type.Literal('toggle'),
      Type.Literal('move'),
    ]),
    atMs: Type.Number({ minimum: 0 }),
    before: Type.Array(SegmentInput),
    after: Type.Array(SegmentInput),
  },
  { additionalProperties: false },
);

const CreateBody = Type.Object(
  {
    parentSetId: Type.String(),
    segments: Type.Array(SegmentInput),
    editLog: Type.Union([Type.Array(EditOp), Type.Null()]),
    isFinal: Type.Boolean(),
  },
  { additionalProperties: false },
);

/** Reading segment sets and saving the user's corrected ones (`/v1/videos/:id/segment-sets`, `/v1/segment-sets/:id`). */
export const segmentSetRoutes: FastifyPluginCallbackTypebox = (app, _options, done) => {
  app.addSchema(SegmentSet);

  const security = [{ bearerAuth: [] }];
  const error = Type.Ref('ErrorResponse');

  app.get(
    '/videos/:id/segment-sets',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['segment-sets'],
        security,
        params: IdParams,
        response: { 200: Type.Object({ items: Type.Array(Type.Ref('SegmentSet')) }), 401: error, 404: error },
      },
    },
    async (request) => {
      const notFound = new AppError('not_found', 'Video not found');
      if (!UUID_PATTERN.test(request.params.id)) throw notFound;
      const video = await app.db
        .selectFrom('videos')
        .select('id')
        .where('id', '=', request.params.id)
        .where('user_id', '=', request.user.sub)
        .executeTakeFirst();
      if (video === undefined) throw notFound;

      const rows = await app.db
        .selectFrom('segment_sets')
        .selectAll()
        .where('video_id', '=', video.id)
        .orderBy('created_at')
        .orderBy('id')
        .execute();
      return { items: rows.map(toSegmentSet) };
    },
  );

  app.get(
    '/segment-sets/:id',
    {
      onRequest: app.authenticate,
      schema: {
        tags: ['segment-sets'],
        security,
        params: IdParams,
        response: { 200: Type.Ref('SegmentSet'), 401: error, 404: error },
      },
    },
    async (request) => {
      if (!UUID_PATTERN.test(request.params.id)) throw new AppError('not_found', 'Segment set not found');
      // Joining through the video restricts the lookup to the caller's own sets; others look like missing ones.
      const row = await app.db
        .selectFrom('segment_sets')
        .innerJoin('videos', 'videos.id', 'segment_sets.video_id')
        .selectAll('segment_sets')
        .where('segment_sets.id', '=', request.params.id)
        .where('videos.user_id', '=', request.user.sub)
        .executeTakeFirst();
      if (row === undefined) throw new AppError('not_found', 'Segment set not found');
      return toSegmentSet(row);
    },
  );

  app.post(
    '/videos/:id/segment-sets',
    {
      onRequest: app.authenticate,
      bodyLimit: USER_SET_BODY_LIMIT,
      schema: {
        tags: ['segment-sets'],
        security,
        params: IdParams,
        body: CreateBody,
        response: { 201: Type.Ref('SegmentSet'), 400: error, 401: error, 404: error, 413: error },
      },
    },
    async (request, reply) => {
      const notFound = new AppError('not_found', 'Video not found');
      if (!UUID_PATTERN.test(request.params.id)) throw notFound;
      const { parentSetId, segments, editLog, isFinal } = request.body;

      const row = await app.db.transaction().execute(async (trx) => {
        // The row lock serializes concurrent saves of one video, so "exactly one final set" cannot be violated
        // by two requests each un-finaling the other's predecessor.
        const video = await trx
          .selectFrom('videos')
          .selectAll()
          .where('id', '=', request.params.id)
          .where('user_id', '=', request.user.sub)
          .forUpdate()
          .executeTakeFirst();
        if (video === undefined) throw notFound;

        assertValidSegments(segments, video.duration_ms);
        for (const [index, op] of (editLog ?? []).entries()) {
          assertValidSegments(op.before, video.duration_ms, `editLog[${String(index)}].before`);
          assertValidSegments(op.after, video.duration_ms, `editLog[${String(index)}].after`);
        }

        if (!UUID_PATTERN.test(parentSetId)) {
          throw new AppError('validation_error', 'parentSetId must belong to this video');
        }
        const parent = await trx
          .selectFrom('segment_sets')
          .select('id')
          .where('id', '=', parentSetId)
          .where('video_id', '=', video.id)
          .executeTakeFirst();
        if (parent === undefined) {
          throw new AppError('validation_error', 'parentSetId must belong to this video');
        }

        if (isFinal) {
          await trx
            .updateTable('segment_sets')
            .set({ is_final: false })
            .where('video_id', '=', video.id)
            .where('is_final', '=', true)
            .execute();
        }

        return trx
          .insertInto('segment_sets')
          .values({
            video_id: video.id,
            kind: 'user',
            parent_set_id: parent.id,
            segments: serializeSegments(segments),
            edit_log:
              editLog === null
                ? null
                : JSON.stringify(
                    editLog.map((op) => ({
                      op: op.op,
                      atMs: op.atMs,
                      before: normalizeSegments(op.before),
                      after: normalizeSegments(op.after),
                    })),
                  ),
            is_final: isFinal,
          })
          .returningAll()
          .executeTakeFirstOrThrow();
      });
      return reply.code(201).send(toSegmentSet(row));
    },
  );

  done();
};
