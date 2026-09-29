import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { buildApp } from '../src/app.js';
import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';

const DURATION_MS = 90_000;
const UNKNOWN_ID = '00000000-0000-4000-8000-000000000000';

interface SegmentSetBody {
  id: string;
  videoId: string;
  kind: string;
  parentSetId: string | null;
  jobId: string | null;
  modelVersion: string | null;
  segments: { startMs: number; endMs: number; label: string; confidence: number | null }[];
  scores: unknown;
  editLog: unknown;
  isFinal: boolean;
}

type Auth = { authorization: string };

describe('segment set routes', () => {
  let schema: TestSchema;
  let app: Awaited<ReturnType<typeof buildApp>>;
  let counter = 0;

  beforeAll(async () => {
    schema = await createTestSchema();
    const db = createDb(schema.config.database);
    try {
      await runMigrations(db);
    } finally {
      await db.destroy();
    }
    app = await buildApp({ config: schema.config });
  });

  afterAll(async () => {
    await app.close();
    await schema.drop();
  });

  async function newUser(): Promise<{ id: string; auth: Auth }> {
    counter += 1;
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: `set-user${String(counter)}@example.com`, password: 'correct horse' },
    });
    const body = response.json<{ token: string; user: { id: string } }>();
    return { id: body.user.id, auth: { authorization: `Bearer ${body.token}` } };
  }

  /** A video with a prediction set, inserted directly so the tests do not depend on uploads and the worker. */
  async function seedVideo(userId: string): Promise<{ videoId: string; predictionId: string }> {
    counter += 1;
    const video = await app.db
      .insertInto('videos')
      .values({
        user_id: userId,
        filename: 'match.mov',
        duration_ms: DURATION_MS,
        width: 854,
        height: 480,
        fps: 15,
        proxy_size_bytes: 2048,
        status: 'analyzed',
        object_key: `proxies/${userId}/set-${String(counter)}.mp4`,
      })
      .returning('id')
      .executeTakeFirstOrThrow();
    const prediction = await app.db
      .insertInto('segment_sets')
      .values({
        video_id: video.id,
        kind: 'prediction',
        model_version: 'heuristic-1',
        segments: JSON.stringify([{ startMs: 1000, endMs: 9000, label: 'rally', confidence: 0.9 }]),
        scores: JSON.stringify({ hz: 2, values: [0.1, 0.9] }),
      })
      .returning('id')
      .executeTakeFirstOrThrow();
    return { videoId: video.id, predictionId: prediction.id };
  }

  async function save(auth: Auth, videoId: string, payload: unknown) {
    return app.inject({
      method: 'POST',
      url: `/v1/videos/${videoId}/segment-sets`,
      headers: auth,
      payload: payload as object,
    });
  }

  const segments = [
    { startMs: 1000, endMs: 5000, label: 'rally' },
    { startMs: 5000, endMs: 8000, label: 'rally', confidence: null },
  ];

  it('requires authentication', async () => {
    expect(
      (await app.inject({ method: 'GET', url: `/v1/videos/${UNKNOWN_ID}/segment-sets` })).statusCode,
    ).toBe(401);
    expect((await app.inject({ method: 'GET', url: `/v1/segment-sets/${UNKNOWN_ID}` })).statusCode).toBe(401);
    const post = await app.inject({
      method: 'POST',
      url: `/v1/videos/${UNKNOWN_ID}/segment-sets`,
      payload: { parentSetId: UNKNOWN_ID, segments: [], editLog: null, isFinal: false },
    });
    expect(post.statusCode).toBe(401);
  });

  describe('reading', () => {
    it('lists the sets of a video oldest first and returns a single one', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const saved = await save(user.auth, videoId, {
        parentSetId: predictionId,
        segments,
        editLog: null,
        isFinal: false,
      });
      expect(saved.statusCode).toBe(201);

      const list = await app.inject({
        method: 'GET',
        url: `/v1/videos/${videoId}/segment-sets`,
        headers: user.auth,
      });
      expect(list.statusCode).toBe(200);
      const items = list.json<{ items: SegmentSetBody[] }>().items;
      expect(items.map((item) => item.kind)).toEqual(['prediction', 'user']);
      expect(items[0]).toMatchObject({
        id: predictionId,
        videoId,
        parentSetId: null,
        modelVersion: 'heuristic-1',
        scores: { hz: 2, values: [0.1, 0.9] },
        editLog: null,
        isFinal: false,
      });

      const single = await app.inject({
        method: 'GET',
        url: `/v1/segment-sets/${predictionId}`,
        headers: user.auth,
      });
      expect(single.statusCode).toBe(200);
      expect(single.json<SegmentSetBody>().segments).toEqual([
        { startMs: 1000, endMs: 9000, label: 'rally', confidence: 0.9 },
      ]);
    });

    it('hides the sets of other users and unknown or malformed ids', async () => {
      const owner = await newUser();
      const other = await newUser();
      const { videoId, predictionId } = await seedVideo(owner.id);

      for (const url of [
        `/v1/videos/${videoId}/segment-sets`,
        `/v1/segment-sets/${predictionId}`,
        `/v1/videos/${UNKNOWN_ID}/segment-sets`,
        `/v1/segment-sets/${UNKNOWN_ID}`,
        '/v1/videos/not-a-uuid/segment-sets',
        '/v1/segment-sets/not-a-uuid',
      ]) {
        const response = await app.inject({ method: 'GET', url, headers: other.auth });
        expect(response.statusCode, url).toBe(404);
      }
    });
  });

  describe('saving a user set', () => {
    it('stores the segments, the edit log and the parent', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const editLog = [
        {
          op: 'trim',
          atMs: 1_700_000_000_000,
          before: [{ startMs: 1000, endMs: 9000, label: 'rally', confidence: 0.9 }],
          after: [{ startMs: 1000, endMs: 5000, label: 'rally', confidence: 0.9 }],
        },
      ];

      const response = await save(user.auth, videoId, {
        parentSetId: predictionId,
        segments,
        editLog,
        isFinal: false,
      });
      expect(response.statusCode).toBe(201);
      expect(response.json<SegmentSetBody>()).toMatchObject({
        videoId,
        kind: 'user',
        parentSetId: predictionId,
        jobId: null,
        modelVersion: null,
        scores: null,
        isFinal: false,
        editLog,
        segments: [
          { startMs: 1000, endMs: 5000, label: 'rally', confidence: null },
          { startMs: 5000, endMs: 8000, label: 'rally', confidence: null },
        ],
      });
    });

    it('stores edit log snapshots without a confidence as null, keeping the set readable', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const response = await save(user.auth, videoId, {
        parentSetId: predictionId,
        segments,
        editLog: [
          { op: 'add', atMs: 1, before: [], after: [{ startMs: 1000, endMs: 2000, label: 'rally' }] },
        ],
        isFinal: false,
      });
      expect(response.statusCode).toBe(201);
      const created = response.json<SegmentSetBody>();
      expect(created.editLog).toEqual([
        {
          op: 'add',
          atMs: 1,
          before: [],
          after: [{ startMs: 1000, endMs: 2000, label: 'rally', confidence: null }],
        },
      ]);

      const list = await app.inject({
        method: 'GET',
        url: `/v1/videos/${videoId}/segment-sets`,
        headers: user.auth,
      });
      expect(list.statusCode).toBe(200);
      expect(list.json<{ items: SegmentSetBody[] }>().items.map((item) => item.id)).toContain(created.id);
    });

    it('accepts an empty segment list', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const response = await save(user.auth, videoId, {
        parentSetId: predictionId,
        segments: [],
        editLog: [],
        isFinal: true,
      });
      expect(response.statusCode).toBe(201);
    });

    it('requires a parent set of the same video', async () => {
      const user = await newUser();
      const first = await seedVideo(user.id);
      const second = await seedVideo(user.id);

      const foreignParent = await save(user.auth, first.videoId, {
        parentSetId: second.predictionId,
        segments,
        editLog: null,
        isFinal: false,
      });
      expect(foreignParent.statusCode).toBe(400);
      expect(foreignParent.json<{ error: { code: string } }>().error.code).toBe('validation_error');

      for (const parentSetId of [UNKNOWN_ID, 'not-a-uuid']) {
        const response = await save(user.auth, first.videoId, {
          parentSetId,
          segments,
          editLog: null,
          isFinal: false,
        });
        expect(response.statusCode, parentSetId).toBe(400);
      }

      const missing = await save(user.auth, first.videoId, { segments, editLog: null, isFinal: false });
      expect(missing.statusCode).toBe(400);
    });

    it('does not let a user save into another user’s video', async () => {
      const owner = await newUser();
      const other = await newUser();
      const { videoId, predictionId } = await seedVideo(owner.id);
      const response = await save(other.auth, videoId, {
        parentSetId: predictionId,
        segments,
        editLog: null,
        isFinal: false,
      });
      expect(response.statusCode).toBe(404);
    });

    it('rejects invalid segments, including those inside the edit log', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const base = { parentSetId: predictionId, editLog: null, isFinal: false };

      const bad: unknown[] = [
        [{ startMs: 5000, endMs: 5000, label: 'rally' }],
        [{ startMs: 0, endMs: DURATION_MS + 1, label: 'rally' }],
        [
          { startMs: 4000, endMs: 6000, label: 'rally' },
          { startMs: 5000, endMs: 7000, label: 'rally' },
        ],
        [
          { startMs: 6000, endMs: 7000, label: 'rally' },
          { startMs: 1000, endMs: 2000, label: 'rally' },
        ],
        [{ startMs: 1000, endMs: 2000, label: 'timeout' }],
        [{ startMs: -1, endMs: 2000, label: 'rally' }],
      ];
      for (const candidate of bad) {
        const response = await save(user.auth, videoId, { ...base, segments: candidate });
        expect(response.statusCode, JSON.stringify(candidate)).toBe(400);
      }

      const badLog = await save(user.auth, videoId, {
        ...base,
        segments,
        editLog: [
          {
            op: 'add',
            atMs: 1,
            before: [],
            after: [{ startMs: 1000, endMs: DURATION_MS + 5, label: 'rally' }],
          },
        ],
      });
      expect(badLog.statusCode).toBe(400);
      expect(badLog.json<{ error: { message: string } }>().error.message).toContain('editLog[0].after');

      const badOp = await save(user.auth, videoId, {
        ...base,
        segments,
        editLog: [{ op: 'explode', atMs: 1, before: [], after: [] }],
      });
      expect(badOp.statusCode).toBe(400);
    });

    it('keeps exactly one final set per video', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const other = await seedVideo(user.id);
      const body = { parentSetId: predictionId, segments, editLog: null };

      const first = (await save(user.auth, videoId, { ...body, isFinal: true })).json<SegmentSetBody>();
      const draft = (await save(user.auth, videoId, { ...body, isFinal: false })).json<SegmentSetBody>();
      expect(first.isFinal).toBe(true);
      expect(draft.isFinal).toBe(false);

      const otherFinal = (
        await save(user.auth, other.videoId, {
          parentSetId: other.predictionId,
          segments,
          editLog: null,
          isFinal: true,
        })
      ).json<SegmentSetBody>();

      const second = (
        await save(user.auth, videoId, { ...body, parentSetId: first.id, isFinal: true })
      ).json<SegmentSetBody>();
      expect(second.isFinal).toBe(true);

      const list = await app.inject({
        method: 'GET',
        url: `/v1/videos/${videoId}/segment-sets`,
        headers: user.auth,
      });
      const finals = list
        .json<{ items: SegmentSetBody[] }>()
        .items.filter((item) => item.isFinal)
        .map((item) => item.id);
      expect(finals).toEqual([second.id]);

      // Another video's final set is not touched.
      const stillFinal = await app.inject({
        method: 'GET',
        url: `/v1/segment-sets/${otherFinal.id}`,
        headers: user.auth,
      });
      expect(stillFinal.json<SegmentSetBody>().isFinal).toBe(true);
    });

    it('leaves the earlier final set alone when the new one is rejected', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const body = { parentSetId: predictionId, editLog: null };
      const first = (
        await save(user.auth, videoId, { ...body, segments, isFinal: true })
      ).json<SegmentSetBody>();

      const rejected = await save(user.auth, videoId, {
        ...body,
        segments: [{ startMs: 10, endMs: DURATION_MS + 1, label: 'rally' }],
        isFinal: true,
      });
      expect(rejected.statusCode).toBe(400);

      const after = await app.inject({
        method: 'GET',
        url: `/v1/segment-sets/${first.id}`,
        headers: user.auth,
      });
      expect(after.json<SegmentSetBody>().isFinal).toBe(true);
    });

    it('accepts an edit log of a few megabytes and rejects one above 5 MB', async () => {
      const user = await newUser();
      const { videoId, predictionId } = await seedVideo(user.id);
      const op = { op: 'move', atMs: 1, before: [], after: [] };
      const logOfBytes = (bytes: number) =>
        Array.from({ length: Math.ceil(bytes / JSON.stringify(op).length) }, () => op);

      const large = await save(user.auth, videoId, {
        parentSetId: predictionId,
        segments,
        editLog: logOfBytes(4 * 1024 * 1024),
        isFinal: false,
      });
      expect(large.statusCode).toBe(201);

      const tooLarge = await save(user.auth, videoId, {
        parentSetId: predictionId,
        segments,
        editLog: logOfBytes(5.5 * 1024 * 1024),
        isFinal: false,
      });
      expect(tooLarge.statusCode).toBe(413);
      expect(tooLarge.json<{ error: { code: string } }>().error.code).toBe('payload_too_large');
    });
  });
});
