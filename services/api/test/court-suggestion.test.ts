import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { buildApp } from '../src/app.js';
import type { TestSchema } from './helpers/db.js';
import { createMigratedTestSchema, insertVideo, registerUser } from './helpers/seed.js';

const INTERNAL = { authorization: 'Bearer test-internal-token-0123456789' };
const COURT = { roi: { x: 0.1, y: 0.2, width: 0.6, height: 0.7 }, netPoint: { x: 0.4, y: 0.55 } };
const SUGGESTION = { court: COURT, confidence: 0.83 };

describe('court suggestion', () => {
  let schema: TestSchema;
  let app: Awaited<ReturnType<typeof buildApp>>;
  let counter = 0;

  beforeAll(async () => {
    schema = await createMigratedTestSchema();
    app = await buildApp({ config: schema.config });
  });

  afterAll(async () => {
    await app.close();
    await schema.drop();
  });

  async function newUser() {
    counter += 1;
    return registerUser(app, `court-user${String(counter)}@example.com`);
  }

  /** Inserts an uploaded video with a queued detection task, as `upload-complete` leaves it. */
  async function seedVideoWithTask(userId: string): Promise<string> {
    counter += 1;
    const videoId = await insertVideo(app.db, {
      user_id: userId,
      status: 'uploaded',
      object_key: `proxies/${userId}/court-${String(counter)}.mp4`,
    });
    await app.db.insertInto('court_detection_tasks').values({ video_id: videoId }).execute();
    return videoId;
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

  /** Reports a detection outcome as the claim `workerId` / `attempt` (by default the first claim of worker-1). */
  async function report(videoId: string, suggestion: unknown, workerId = 'worker-1', attempt = 1) {
    return app.inject({
      method: 'POST',
      url: `/internal/videos/${videoId}/court-suggestion`,
      headers: INTERNAL,
      payload: { workerId, attempt, suggestion },
    });
  }

  /** Makes the lease of the task expire, so that the next claim takes it over. */
  async function expireLease(videoId: string) {
    await app.db
      .updateTable('court_detection_tasks')
      .set({ lease_expires_at: new Date(Date.now() - 1000) })
      .where('video_id', '=', videoId)
      .execute();
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
    expect(claimed.json<{ videoId: string; proxyUrl: string }>()).toMatchObject({ videoId, attempt: 1 });
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

    const response = await report(videoId, null);

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
    for (const suggestion of bad) {
      const response = await report(videoId, suggestion);
      expect(response.statusCode, JSON.stringify(suggestion)).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    }
    for (const payload of [{ suggestion: SUGGESTION }, { workerId: 'worker-1', suggestion: SUGGESTION }]) {
      const response = await app.inject({
        method: 'POST',
        url: `/internal/videos/${videoId}/court-suggestion`,
        headers: INTERNAL,
        payload,
      });
      expect(response.statusCode, JSON.stringify(payload)).toBe(400);
    }
    expect(await fetchVideo(user.auth, videoId)).toMatchObject({ courtSuggestion: null });
  });

  it('requires the internal token', async () => {
    const { user, videoId } = await freshTask();
    const attempts = [
      {
        url: `/internal/videos/${videoId}/court-suggestion`,
        payload: { workerId: 'w', attempt: 1, suggestion: SUGGESTION },
      },
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
    async function fail(videoId: string, retryable: boolean, workerId = 'worker-1', attempt = 1) {
      return app.inject({
        method: 'POST',
        url: `/internal/videos/${videoId}/court-suggestion/fail`,
        headers: INTERNAL,
        payload: { workerId, attempt, error: 'boom', retryable },
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
        expect((await fail(videoId, true, 'worker-1', attempt)).statusCode).toBe(204);
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

    it('fails the task for good instead of handing out a proxy replaced after upload-complete', async () => {
      const { user, videoId } = await freshTask();
      await app.db
        .updateTable('videos')
        .set({ proxy_etag: '"recorded"' })
        .where('id', '=', videoId)
        .execute();
      const objectInfo = vi.spyOn(app.storage, 'objectInfo');
      objectInfo.mockResolvedValue({ sizeBytes: 2048, etag: '"replaced"' });
      let response;
      try {
        response = await claim();
      } finally {
        objectInfo.mockRestore();
      }

      expect(response.statusCode).toBe(204);
      expect(await taskOf(videoId)).toMatchObject({
        status: 'failed',
        attempts: 0,
        error: expect.stringContaining('changed') as string,
      });
      expect(await fetchVideo(user.auth, videoId)).toMatchObject({ courtSuggestion: null });
      expect((await claim()).statusCode).toBe(204);
    });

    it('hands out the task when the proxy still has the recorded ETag', async () => {
      const { videoId } = await freshTask();
      await app.db
        .updateTable('videos')
        .set({ proxy_etag: '"recorded"' })
        .where('id', '=', videoId)
        .execute();
      const objectInfo = vi.spyOn(app.storage, 'objectInfo');
      objectInfo.mockResolvedValue({ sizeBytes: 2048, etag: '"recorded"' });
      let response;
      try {
        response = await claim();
      } finally {
        objectInfo.mockRestore();
      }

      expect(response.statusCode).toBe(200);
      expect(response.json<{ videoId: string }>().videoId).toBe(videoId);
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

    it('rejects a failure report from a worker whose lease was taken over', async () => {
      const { videoId } = await freshTask('dead-worker');
      await app.db
        .updateTable('court_detection_tasks')
        .set({ lease_expires_at: new Date(Date.now() - 1000) })
        .where('video_id', '=', videoId)
        .execute();
      expect((await claim('worker-2')).statusCode).toBe(200);

      const stale = await fail(videoId, true, 'dead-worker');

      expect(stale.statusCode).toBe(409);
      expect(await taskOf(videoId)).toMatchObject({ status: 'running', worker_id: 'worker-2', attempts: 2 });
    });

    it('rejects stale reports of an earlier attempt when the same workerId re-claimed the task', async () => {
      const { user, videoId } = await freshTask('shared-worker');
      await expireLease(videoId);
      const reclaimed = await claim('shared-worker');
      expect(reclaimed.json<{ attempt: number }>().attempt).toBe(2);

      const staleFail = await fail(videoId, true, 'shared-worker', 1);
      const staleResult = await report(videoId, SUGGESTION, 'shared-worker', 1);

      expect([staleFail.statusCode, staleResult.statusCode]).toEqual([409, 409]);
      expect(await taskOf(videoId)).toMatchObject({ status: 'running', attempts: 2 });
      expect(await fetchVideo(user.auth, videoId)).toMatchObject({ courtSuggestion: null });

      expect((await report(videoId, SUGGESTION, 'shared-worker', 2)).statusCode).toBe(204);
      expect(await fetchVideo(user.auth, videoId)).toMatchObject({ courtSuggestion: SUGGESTION });
    });

    it('rejects a result from a worker whose lease was taken over', async () => {
      const { videoId } = await freshTask('dead-worker');
      await expireLease(videoId);
      expect((await claim('worker-2')).statusCode).toBe(200);

      expect((await report(videoId, SUGGESTION, 'dead-worker', 1)).statusCode).toBe(409);
      expect(await taskOf(videoId)).toMatchObject({ status: 'running', worker_id: 'worker-2' });
    });
  });
});
