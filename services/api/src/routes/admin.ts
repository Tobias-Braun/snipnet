import { Readable } from 'node:stream';

import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import { sql, type Selectable } from 'kysely';
import Type from 'typebox';

import type { SegmentSetsTable } from '../db/types.js';
import { requireInternalToken } from '../plugins/internal-auth.js';
import { toSegmentSet, toVideo } from '../serialize.js';

/** Final sets are read from the database in pages of this size while the response streams. */
const PAGE_SIZE = 50;

/** Guards against a corrupt `parent_set_id` cycle; real edit chains are a handful of sets long. */
const MAX_LINEAGE_DEPTH = 1000;

/**
 * Position after the last exported final set. `createdAt` is Postgres' own text form of the timestamp: a `Date`
 * only keeps milliseconds, and comparing a truncated value against the microsecond column would return the last
 * row of a page again on the next one.
 */
interface Cursor {
  createdAt: string;
  id: string;
}

/** Operator endpoints under `/v1/admin/*`, guarded by `ADMIN_TOKEN` instead of a user token. */
export const adminRoutes: FastifyPluginCallbackTypebox<{ adminToken: string }> = (app, options, done) => {
  app.addHook('onRequest', requireInternalToken(options.adminToken, 'admin'));

  const security = [{ adminToken: [] }];
  const error = Type.Ref('ErrorResponse');

  /**
   * Follows `parent_set_id` from a final user set up to the prediction it descends from, or returns `null` when
   * the chain does not end in one (such a set has no prediction to compare against and is unusable as a label).
   */
  async function findPrediction(
    start: Selectable<SegmentSetsTable>,
  ): Promise<Selectable<SegmentSetsTable> | null> {
    let current = start;
    for (let depth = 0; depth < MAX_LINEAGE_DEPTH; depth += 1) {
      if (current.kind === 'prediction') return current;
      if (current.parent_set_id === null) return null;
      const parent = await app.db
        .selectFrom('segment_sets')
        .selectAll()
        .where('id', '=', current.parent_set_id)
        .executeTakeFirst();
      if (parent === undefined) return null;
      current = parent;
    }
    return null;
  }

  /** One page of final user sets of consenting users, newest-per-video only, after `since` and `cursor`. */
  function nextFinalSets(since: Date | null, cursor: Cursor | null) {
    return (
      app.db
        .selectFrom('segment_sets as ss')
        .innerJoin('videos as v', 'v.id', 'ss.video_id')
        .innerJoin('users as u', 'u.id', 'v.user_id')
        .selectAll('ss')
        .select(sql<string>`ss.created_at::text`.as('created_at_text'))
        .where('ss.kind', '=', 'user')
        .where('ss.is_final', '=', true)
        .where('u.training_consent', '=', true)
        // A video with several final sets contributes only its newest one: no later final set of it exists.
        .where((eb) =>
          eb.not(
            eb.exists(
              eb
                .selectFrom('segment_sets as newer')
                .select('newer.id')
                .whereRef('newer.video_id', '=', 'ss.video_id')
                .where('newer.kind', '=', 'user')
                .where('newer.is_final', '=', true)
                .where((n) =>
                  n.or([
                    n('newer.created_at', '>', n.ref('ss.created_at')),
                    n.and([
                      n('newer.created_at', '=', n.ref('ss.created_at')),
                      n('newer.id', '>', n.ref('ss.id')),
                    ]),
                  ]),
                ),
            ),
          ),
        )
        .$if(since !== null, (qb) => qb.where('ss.created_at', '>=', since as Date))
        .$if(cursor !== null, (qb) => {
          const after = cursor as Cursor;
          const afterTime = sql<Date>`${after.createdAt}::timestamptz`;
          return qb.where((eb) =>
            eb.or([
              eb('ss.created_at', '>', afterTime),
              eb.and([eb('ss.created_at', '=', afterTime), eb('ss.id', '>', after.id)]),
            ]),
          );
        })
        .orderBy('ss.created_at')
        .orderBy('ss.id')
        .limit(PAGE_SIZE)
        .execute()
    );
  }

  /**
   * Yields one NDJSON line per exportable video. Pages are keyed on the final set's `(created_at, id)`, so the
   * export needs no server-side state and stays consistent while rows are inserted concurrently.
   */
  async function* exportLines(since: Date | null): AsyncGenerator<string> {
    let cursor: Cursor | null = null;
    for (;;) {
      const finals = await nextFinalSets(since, cursor);
      for (const final of finals) {
        const prediction = await findPrediction(final);
        if (prediction === null) continue;
        const video = await app.db
          .selectFrom('videos')
          .selectAll()
          .where('id', '=', final.video_id)
          .executeTakeFirst();
        // Deleted since its page was read; failing here would cut off the rest of an otherwise valid export.
        if (video === undefined) continue;
        yield `${JSON.stringify({
          video: toVideo(video, undefined),
          proxyUrl: await app.storage.presignExportDownload(video.object_key),
          prediction: toSegmentSet(prediction),
          final: toSegmentSet(final),
        })}\n`;
      }

      const last = finals.at(-1);
      if (last === undefined || finals.length < PAGE_SIZE) return;
      cursor = { createdAt: last.created_at_text, id: last.id };
    }
  }

  app.get(
    '/training-export',
    {
      schema: {
        tags: ['admin'],
        security,
        description:
          'NDJSON stream, one line per video with a final user set of a consenting user: ' +
          '`{ video, proxyUrl, prediction, final }`. `since` keeps final sets created at or after that instant.',
        querystring: Type.Object({ since: Type.Optional(Type.String({ format: 'date-time' })) }),
        response: { 400: error, 401: error },
      },
    },
    async (request, reply) => {
      const since = request.query.since === undefined ? null : new Date(request.query.since);
      const stream = Readable.from(exportLines(since));
      stream.on('error', (cause: unknown) => {
        // Headers are already sent, so all that is left is to log; the connection is cut and the consumer sees
        // a truncated stream instead of a complete-looking one.
        request.log.error({ err: cause }, 'training export failed');
      });
      return reply.type('application/x-ndjson').send(stream);
    },
  );

  done();
};
