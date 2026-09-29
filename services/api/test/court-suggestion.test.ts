import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { buildApp } from '../src/app.js';
import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';

const INTERNAL = { authorization: 'Bearer test-internal-token-0123456789' };
const COURT = { roi: { x: 0.1, y: 0.2, width: 0.6, height: 0.7 }, netPoint: { x: 0.4, y: 0.55 } };
const SUGGESTION = { court: COURT, confidence: 0.83 };

describe('court suggestion', () => {
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

  async function newUser(): Promise<{ id: string; auth: { authorization: string } }> {
    counter += 1;
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: `court-user${String(counter)}@example.com`, password: 'correct horse' },
    });
    const body = response.json<{ token: string; user: { id: string } }>();
    return { id: body.user.id, auth: { authorization: `Bearer ${body.token}` } };
  }

  /** Inserts an uploaded video with a queued detection task, as `upload-complete` leaves it. */
  async function seedVideoWithTask(userId: string): Promise<string> {
    counter += 1;
    const row = await app.db
      .insertInto('videos')
      .values({
        user_id: userId,
        filename: 'match.mov',
        duration_ms: 90_000,
        width: 854,
        height: 480,
        fps: 15,
        proxy_size_bytes: 2048,
        status: 'uploaded',
        object_key: `proxies/${userId}/court-${String(counter)}.mp4`,
      })
      .returning('id')
      .executeTakeFirstOrThrow();
    await app.db.insertInto('court_detection_tasks').values({ video_id: row.id }).execute();
    return row.id;
  }

  /**
   * Drains the queue so tests that claim are not disturbed by tasks of earlier tests, then queues a task for a new
   * video, and leases it to `claimedBy` when given.
   */
  async function freshTask(claimedBy?: string) {
    await app.db.deleteFrom('court_detection_tasks').execute();
    const user = await newUser();
    const videoId = await seedVideoWithTask(user.id);
    if (claimedBy !== undefined) await claim(claimedBy);
    return { user, videoId };
  }

  async function claim(workerId = 'worker-1') {
    return app.inject({
      method: 'POST',
      url: '/internal/court-detection/claim',
      headers: INTERNAL,
      payload: { workerId },
    });
  }

  async function report(videoId: string, payload: unknown) {
    return app.inject({
      method: 'POST',
      url: `/internal/videos/${videoId}/court-suggestion`,
      headers: INTERNAL,
      payload: payload as object,
    });
  }

  async function fetchVideo(auth: { authorization: string }, videoId: string) {
    const response = await app.inject({ method: 'GET', url: `/v1/videos/${videoId}`, headers: auth });
    return response.json<{ court: unknown; courtSuggestion: unknown }>();
  }

  it('serves courtSuggestion as null by default', async () => {
    const { user, videoId } = await freshTask();
    expect(await fetchVideo(user.auth, videoId)).toMatchObject({ court: null, courtSuggestion: null });
  });

  it('hands out the task with a proxy URL and stores the reported suggestion without touching the court', async () => {
    const { user, videoId } = await freshTask();

    const claimed = await claim();
    expect(claimed.statusCode).toBe(200);
    expect(claimed.json<{ videoId: string; proxyUrl: string }>()).toMatchObject({ videoId });
    expect(claimed.json<{ proxyUrl: string }>().proxyUrl).toContain(`court-${String(counter)}.mp4`);

    expect((await report(videoId, SUGGESTION)).statusCode).toBe(204);

    expect(await fetchVideo(user.auth, videoId)).toMatchObject({ court: null, courtSuggestion: SUGGESTION });
    const list = await app.inject({ method: 'GET', url: '/v1/videos', headers: user.auth });
    expect(list.json<{ items: { courtSuggestion: unknown }[] }>().items[0]?.courtSuggestion).toEqual(
      SUGGESTION,
    );
  });

  it('records "no net found" as a null suggestion and does not offer the finished task again', async () => {
    const { user, videoId } = await freshTask('worker-1');

    const response = await app.inject({
      method: 'POST',
      url: `/internal/videos/${videoId}/court-suggestion`,
      headers: { ...INTERNAL, 'content-type': 'application/json' },
      payload: 'null',
    });

    expect(response.statusCode).toBe(204);
    expect(await fetchVideo(user.auth, videoId)).toMatchObject({ courtSuggestion: null });
    expect((await claim()).statusCode).toBe(204);
  });

  it('rejects out-of-range values with validation_error', async () => {
    const { user, videoId } = await freshTask('worker-1');

    const bad = [
      { ...SUGGESTION, confidence: 1.2 },
      { ...SUGGESTION, confidence: -0.1 },
      { court: { ...COURT, netPoint: { x: 1.5, y: 0.5 } }, confidence: 0.5 },
      { court: { ...COURT, roi: { ...COURT.roi, x: -0.1 } }, confidence: 0.5 },
      { court: { ...COURT, roi: { ...COURT.roi, width: 0 } }, confidence: 0.5 },
      { court: { ...COURT, roi: { x: 0.6, y: 0.1, width: 0.6, height: 0.5 } }, confidence: 0.5 },
      { court: COURT },
    ];
    for (const payload of bad) {
      const response = await report(videoId, payload);
      expect(response.statusCode, JSON.stringify(payload)).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    }
    expect(await fetchVideo(user.auth, videoId)).toMatchObject({ courtSuggestion: null });
  });

  it('requires the internal token', async () => {
    const { user, videoId } = await freshTask();
    const attempts = [
      { url: `/internal/videos/${videoId}/court-suggestion`, payload: SUGGESTION },
      { url: '/internal/court-detection/claim', payload: { workerId: 'w' } },
    ];
    for (const { url, payload } of attempts) {
      const anonymous = await app.inject({ method: 'POST', url, payload });
      const asUser = await app.inject({ method: 'POST', url, payload, headers: user.auth });
      expect([anonymous.statusCode, asUser.statusCode]).toEqual([401, 401]);
    }
  });

  it('answers 404 for unknown videos and 409 for a task that is not running', async () => {
    const { videoId } = await freshTask();
    const unknown = await report('11111111-1111-4111-8111-111111111111', SUGGESTION);
    expect(unknown.statusCode).toBe(404);
    expect((await report('not-a-uuid', SUGGESTION)).statusCode).toBe(404);
    expect((await report(videoId, SUGGESTION)).statusCode).toBe(409);
  });

  describe('failures', () => {
    async function fail(videoId: string, retryable: boolean) {
      return app.inject({
        method: 'POST',
        url: `/internal/videos/${videoId}/court-suggestion/fail`,
        headers: INTERNAL,
        payload: { error: 'boom', retryable },
      });
    }

    async function taskOf(videoId: string) {
      return app.db
        .selectFrom('court_detection_tasks')
        .selectAll()
        .where('video_id', '=', videoId)
        .executeTakeFirstOrThrow();
    }

    it('requeues a retryable failure until the attempts are spent', async () => {
      const { user, videoId } = await freshTask();

      for (let attempt = 1; attempt <= 3; attempt += 1) {
        expect((await claim()).statusCode).toBe(200);
        expect((await fail(videoId, true)).statusCode).toBe(204);
      }

      expect((await claim()).statusCode).toBe(204);
      expect(await taskOf(videoId)).toMatchObject({ status: 'failed', attempts: 3, error: 'boom' });
      expect(await fetchVideo(user.auth, videoId)).toMatchObject({ courtSuggestion: null });
    });

    it('fails a non-retryable error for good and leaves the video usable', async () => {
      const { videoId } = await freshTask('worker-1');

      expect((await fail(videoId, false)).statusCode).toBe(204);

      expect((await claim()).statusCode).toBe(204);
      expect(await taskOf(videoId)).toMatchObject({ status: 'failed', attempts: 1 });
      const video = await app.db
        .selectFrom('videos')
        .select('status')
        .where('id', '=', videoId)
        .executeTakeFirstOrThrow();
      expect(video.status).toBe('uploaded');
    });

    it('re-leases a running task whose lease expired', async () => {
      const { videoId } = await freshTask('dead-worker');
      await app.db
        .updateTable('court_detection_tasks')
        .set({ lease_expires_at: new Date(Date.now() - 1000) })
        .where('video_id', '=', videoId)
        .execute();

      const claimed = await claim('worker-2');
      expect(claimed.statusCode).toBe(200);
      expect(claimed.json<{ videoId: string }>().videoId).toBe(videoId);
    });
  });
});
