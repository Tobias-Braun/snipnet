import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { buildApp } from '../src/app.js';
import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';

const INTERNAL = { authorization: 'Bearer test-internal-token-0123456789' };
const COURT = { roi: { x: 0.1, y: 0.2, width: 0.6, height: 0.7 }, netPoint: { x: 0.4, y: 0.55 } };
const DURATION_MS = 90_000;
const PUBLIC_S3_ENDPOINT = 'http://public-s3.example.test:9000';

interface JobBody {
  id: string;
  videoId: string;
  status: string;
  progress: number;
  modelVersion: string | null;
  error: string | null;
  attempts: number;
  startedAt: string | null;
  finishedAt: string | null;
}

interface ClaimBody {
  job: JobBody;
  video: { id: string; status: string; latestJob: JobBody | null };
  proxyUrl: string;
}

describe('job routes', () => {
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
    // A public endpoint that differs from the internal one shows which of them the worker's proxy URL is signed for.
    app = await buildApp({
      config: { ...schema.config, s3: { ...schema.config.s3, publicEndpoint: PUBLIC_S3_ENDPOINT } },
    });
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
      payload: { email: `job-user${String(counter)}@example.com`, password: 'correct horse' },
    });
    const body = response.json<{ token: string; user: { id: string } }>();
    return { id: body.user.id, auth: { authorization: `Bearer ${body.token}` } };
  }

  /** Inserts a video directly, so tests do not depend on the object store that upload-complete would query. */
  async function seedVideo(
    userId: string,
    overrides: { status?: 'created' | 'uploaded' | 'analyzed'; court?: boolean } = {},
  ): Promise<string> {
    const row = await app.db
      .insertInto('videos')
      .values({
        user_id: userId,
        filename: 'match.mov',
        duration_ms: DURATION_MS,
        width: 854,
        height: 480,
        fps: 15,
        proxy_size_bytes: 2048,
        status: overrides.status ?? 'uploaded',
        court: overrides.court === false ? null : JSON.stringify(COURT),
        object_key: `proxies/${userId}/seed-${String(counter)}.mp4`,
      })
      .returning('id')
      .executeTakeFirstOrThrow();
    counter += 1;
    return row.id;
  }

  async function analyze(auth: { authorization: string }, videoId: string) {
    return app.inject({ method: 'POST', url: `/v1/videos/${videoId}/analyze`, headers: auth, payload: {} });
  }

  async function claim(workerId = 'worker-1') {
    return app.inject({
      method: 'POST',
      url: '/internal/jobs/claim',
      headers: INTERNAL,
      payload: { workerId },
    });
  }

  /** Drains the queue so tests that count claims are not disturbed by jobs of earlier tests. */
  async function clearJobs() {
    await app.db.deleteFrom('jobs').execute();
  }

  async function queuedJob(): Promise<{ videoId: string; jobId: string; auth: { authorization: string } }> {
    const user = await newUser();
    const videoId = await seedVideo(user.id);
    const response = await analyze(user.auth, videoId);
    expect(response.statusCode).toBe(202);
    return { videoId, jobId: response.json<JobBody>().id, auth: user.auth };
  }

  async function post(url: string, payload: unknown) {
    return app.inject({ method: 'POST', url, headers: INTERNAL, payload: payload as object });
  }

  describe('analyze', () => {
    it('queues a job and moves the video to analyzing', async () => {
      const user = await newUser();
      const videoId = await seedVideo(user.id);

      const response = await analyze(user.auth, videoId);
      expect(response.statusCode).toBe(202);
      const job = response.json<JobBody>();
      expect(job).toMatchObject({
        videoId,
        status: 'queued',
        progress: 0,
        attempts: 0,
        modelVersion: null,
        error: null,
        startedAt: null,
        finishedAt: null,
      });

      const video = await app.inject({ method: 'GET', url: `/v1/videos/${videoId}`, headers: user.auth });
      expect(video.json<{ status: string; latestJob: JobBody }>()).toMatchObject({
        status: 'analyzing',
        latestJob: { id: job.id },
      });
    });

    it('rejects a second analysis while one is queued', async () => {
      const { videoId, auth } = await queuedJob();
      const response = await analyze(auth, videoId);
      expect(response.statusCode).toBe(409);
      expect(response.json()).toMatchObject({ error: { code: 'conflict' } });
    });

    it('rejects videos that are not uploaded or have no court', async () => {
      const user = await newUser();
      const created = await seedVideo(user.id, { status: 'created' });
      const noCourt = await seedVideo(user.id, { court: false });
      expect((await analyze(user.auth, created)).statusCode).toBe(409);
      expect((await analyze(user.auth, noCourt)).statusCode).toBe(409);
    });

    it('allows a new analysis of an already analyzed video', async () => {
      const user = await newUser();
      const videoId = await seedVideo(user.id, { status: 'analyzed' });
      expect((await analyze(user.auth, videoId)).statusCode).toBe(202);
    });

    it('hides videos of other users and malformed ids', async () => {
      const owner = await newUser();
      const other = await newUser();
      const videoId = await seedVideo(owner.id);
      expect((await analyze(other.auth, videoId)).statusCode).toBe(404);
      expect((await analyze(owner.auth, 'not-a-uuid')).statusCode).toBe(404);
    });

    it('requires a user token', async () => {
      const user = await newUser();
      const videoId = await seedVideo(user.id);
      const anonymous = await app.inject({
        method: 'POST',
        url: `/v1/videos/${videoId}/analyze`,
        payload: {},
      });
      expect(anonymous.statusCode).toBe(401);
      const withInternal = await app.inject({
        method: 'POST',
        url: `/v1/videos/${videoId}/analyze`,
        headers: INTERNAL,
        payload: {},
      });
      expect(withInternal.statusCode).toBe(401);
    });
  });

  describe('GET /v1/jobs/:id', () => {
    it('returns the own job and hides the jobs of others', async () => {
      const { jobId, auth } = await queuedJob();
      const other = await newUser();

      const own = await app.inject({ method: 'GET', url: `/v1/jobs/${jobId}`, headers: auth });
      expect(own.statusCode).toBe(200);
      expect(own.json<JobBody>().id).toBe(jobId);

      const foreign = await app.inject({ method: 'GET', url: `/v1/jobs/${jobId}`, headers: other.auth });
      expect(foreign.statusCode).toBe(404);
      const malformed = await app.inject({ method: 'GET', url: '/v1/jobs/nope', headers: auth });
      expect(malformed.statusCode).toBe(404);
    });

    it('does not accept the internal token', async () => {
      const { jobId } = await queuedJob();
      const response = await app.inject({ method: 'GET', url: `/v1/jobs/${jobId}`, headers: INTERNAL });
      expect(response.statusCode).toBe(401);
    });
  });

  describe('internal authentication', () => {
    it('rejects missing, wrong and user tokens on every internal endpoint', async () => {
      const user = await newUser();
      const id = '00000000-0000-4000-8000-000000000000';
      const endpoints = [
        ['/internal/jobs/claim', { workerId: 'w' }],
        [`/internal/jobs/${id}/progress`, { progress: 0.5 }],
        [`/internal/jobs/${id}/result`, { modelVersion: 'v', segments: [], scores: null }],
        [`/internal/jobs/${id}/fail`, { error: 'x', retryable: false }],
      ] as const;
      for (const [url, payload] of endpoints) {
        const none = await app.inject({ method: 'POST', url, payload });
        expect(none.statusCode, url).toBe(401);
        const wrong = await app.inject({
          method: 'POST',
          url,
          payload,
          headers: { authorization: 'Bearer wrong-token-0123456789' },
        });
        expect(wrong.statusCode, url).toBe(401);
        const jwt = await app.inject({ method: 'POST', url, payload, headers: user.auth });
        expect(jwt.statusCode, url).toBe(401);
      }
    });
  });

  describe('claim', () => {
    it('returns 204 on an empty queue', async () => {
      await clearJobs();
      expect((await claim()).statusCode).toBe(204);
    });

    it('claims the oldest job with a proxy URL and counts the attempt', async () => {
      await clearJobs();
      const first = await queuedJob();
      await queuedJob();

      const response = await claim('worker-a');
      expect(response.statusCode).toBe(200);
      const body = response.json<ClaimBody>();
      expect(body.job).toMatchObject({ id: first.jobId, status: 'running', attempts: 1 });
      expect(body.job.startedAt).not.toBeNull();
      expect(body.video).toMatchObject({ id: first.videoId, status: 'analyzing' });

      const url = new URL(body.proxyUrl);
      // The worker runs next to the API, so the URL has to use the internal S3 endpoint, not the public one.
      expect(url.origin).toBe(new URL(schema.config.s3.endpoint).origin);
      expect(url.pathname).toContain('/proxies/');
      expect(url.searchParams.get('X-Amz-Expires')).toBe('3600');
      expect(url.searchParams.get('X-Amz-Signature')).not.toBeNull();

      const stored = await app.db
        .selectFrom('jobs')
        .select(['worker_id', 'lease_expires_at'])
        .where('id', '=', first.jobId)
        .executeTakeFirstOrThrow();
      expect(stored.worker_id).toBe('worker-a');
      expect(stored.lease_expires_at?.getTime() ?? 0).toBeGreaterThan(Date.now() + 9 * 60_000);
    });

    it('never hands the same job to parallel claims', async () => {
      await clearJobs();
      const jobIds = new Set<string>();
      for (let i = 0; i < 6; i += 1) jobIds.add((await queuedJob()).jobId);

      const responses = await Promise.all(Array.from({ length: 12 }, (_, i) => claim(`worker-${String(i)}`)));

      const claimed = responses.filter((r) => r.statusCode === 200).map((r) => r.json<ClaimBody>().job.id);
      expect(responses.filter((r) => r.statusCode === 204)).toHaveLength(6);
      expect(new Set(claimed).size).toBe(6);
      expect(new Set(claimed)).toEqual(jobIds);
    });

    it('re-claims a running job whose lease expired and skips live leases', async () => {
      await clearJobs();
      const { jobId } = await queuedJob();
      expect((await claim()).statusCode).toBe(200);

      // Lease still valid: nothing to claim.
      expect((await claim('worker-2')).statusCode).toBe(204);

      await app.db
        .updateTable('jobs')
        .set({ lease_expires_at: new Date(Date.now() - 1000) })
        .where('id', '=', jobId)
        .execute();
      const reclaimed = await claim('worker-2');
      expect(reclaimed.statusCode).toBe(200);
      expect(reclaimed.json<ClaimBody>().job).toMatchObject({ id: jobId, attempts: 2, status: 'running' });
    });

    it('fails a job whose lease expired after the last attempt and fails its video', async () => {
      await clearJobs();
      const { jobId, videoId } = await queuedJob();
      await app.db
        .updateTable('jobs')
        .set({ attempts: 3, status: 'running', lease_expires_at: new Date(Date.now() - 1000) })
        .where('id', '=', jobId)
        .execute();

      expect((await claim()).statusCode).toBe(204);
      const job = await app.db
        .selectFrom('jobs')
        .selectAll()
        .where('id', '=', jobId)
        .executeTakeFirstOrThrow();
      expect(job.status).toBe('failed');
      expect(job.error).not.toBeNull();
      const video = await app.db
        .selectFrom('videos')
        .select('status')
        .where('id', '=', videoId)
        .executeTakeFirstOrThrow();
      expect(video.status).toBe('failed');
    });
  });

  describe('progress', () => {
    it('updates progress, extends the lease and never moves backwards', async () => {
      await clearJobs();
      const { jobId, auth } = await queuedJob();
      await claim();
      await app.db
        .updateTable('jobs')
        .set({ lease_expires_at: new Date(Date.now() + 1000) })
        .where('id', '=', jobId)
        .execute();

      expect((await post(`/internal/jobs/${jobId}/progress`, { progress: 0.6 })).statusCode).toBe(204);
      expect((await post(`/internal/jobs/${jobId}/progress`, { progress: 0.2 })).statusCode).toBe(204);

      const job = await app.inject({ method: 'GET', url: `/v1/jobs/${jobId}`, headers: auth });
      expect(job.json<JobBody>().progress).toBe(0.6);
      const stored = await app.db
        .selectFrom('jobs')
        .select('lease_expires_at')
        .where('id', '=', jobId)
        .executeTakeFirstOrThrow();
      expect(stored.lease_expires_at?.getTime() ?? 0).toBeGreaterThan(Date.now() + 9 * 60_000);
    });

    it('validates the value and the job state', async () => {
      await clearJobs();
      const { jobId } = await queuedJob();
      expect((await post(`/internal/jobs/${jobId}/progress`, { progress: 0.5 })).statusCode).toBe(409);
      await claim();
      expect((await post(`/internal/jobs/${jobId}/progress`, { progress: 1.5 })).statusCode).toBe(400);
      expect(
        (await post('/internal/jobs/00000000-0000-4000-8000-000000000000/progress', { progress: 0.5 }))
          .statusCode,
      ).toBe(404);
      expect((await post('/internal/jobs/nope/progress', { progress: 0.5 })).statusCode).toBe(404);
    });
  });

  describe('result', () => {
    const segments = [
      { startMs: 1000, endMs: 5000, label: 'rally', confidence: 0.9 },
      { startMs: 5000, endMs: 9000, label: 'rally', confidence: null },
      { startMs: 20_000, endMs: DURATION_MS, label: 'rally' },
    ];

    it('stores the prediction set and finishes job and video together', async () => {
      await clearJobs();
      const { jobId, videoId, auth } = await queuedJob();
      await claim();

      const scores = { hz: 2, values: [0.1, 0.9, 0.5] };
      const response = await post(`/internal/jobs/${jobId}/result`, {
        modelVersion: 'm-1',
        segments,
        scores,
      });
      expect(response.statusCode).toBe(204);

      const job = await app.inject({ method: 'GET', url: `/v1/jobs/${jobId}`, headers: auth });
      expect(job.json<JobBody>()).toMatchObject({ status: 'succeeded', progress: 1, modelVersion: 'm-1' });
      expect(job.json<JobBody>().finishedAt).not.toBeNull();

      const video = await app.inject({ method: 'GET', url: `/v1/videos/${videoId}`, headers: auth });
      expect(video.json<{ status: string }>().status).toBe('analyzed');

      const set = await app.db
        .selectFrom('segment_sets')
        .selectAll()
        .where('video_id', '=', videoId)
        .executeTakeFirstOrThrow();
      expect(set).toMatchObject({ kind: 'prediction', job_id: jobId, model_version: 'm-1', is_final: false });
      expect(set.segments).toEqual([
        { startMs: 1000, endMs: 5000, label: 'rally', confidence: 0.9 },
        { startMs: 5000, endMs: 9000, label: 'rally', confidence: null },
        { startMs: 20_000, endMs: DURATION_MS, label: 'rally', confidence: null },
      ]);
      expect(set.scores).toEqual(scores);
    });

    it('rejects unsorted, overlapping, empty and out-of-range segments without side effects', async () => {
      await clearJobs();
      const { jobId, videoId } = await queuedJob();
      await claim();

      const invalid = [
        [
          { startMs: 5000, endMs: 6000, label: 'rally' },
          { startMs: 1000, endMs: 2000, label: 'rally' },
        ],
        [
          { startMs: 1000, endMs: 6000, label: 'rally' },
          { startMs: 5000, endMs: 8000, label: 'rally' },
        ],
        [{ startMs: 3000, endMs: 3000, label: 'rally' }],
        [{ startMs: 1000, endMs: DURATION_MS + 1, label: 'rally' }],
      ];
      for (const bad of invalid) {
        const response = await post(`/internal/jobs/${jobId}/result`, {
          modelVersion: 'm',
          segments: bad,
          scores: null,
        });
        expect(response.statusCode).toBe(400);
        expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
      }

      const sets = await app.db
        .selectFrom('segment_sets')
        .select('id')
        .where('video_id', '=', videoId)
        .execute();
      expect(sets).toHaveLength(0);
      const job = await app.db
        .selectFrom('jobs')
        .select('status')
        .where('id', '=', jobId)
        .executeTakeFirstOrThrow();
      expect(job.status).toBe('running');
    });

    it('accepts an empty segment list and rejects a second result', async () => {
      await clearJobs();
      const { jobId } = await queuedJob();
      await claim();
      const body = { modelVersion: 'm', segments: [], scores: null };
      expect((await post(`/internal/jobs/${jobId}/result`, body)).statusCode).toBe(204);
      expect((await post(`/internal/jobs/${jobId}/result`, body)).statusCode).toBe(409);
    });
  });

  describe('fail', () => {
    it('requeues a retryable failure while attempts remain and clears the lease', async () => {
      await clearJobs();
      const { jobId, videoId } = await queuedJob();
      await claim();

      expect(
        (await post(`/internal/jobs/${jobId}/fail`, { error: 'gpu oom', retryable: true })).statusCode,
      ).toBe(204);
      const job = await app.db
        .selectFrom('jobs')
        .selectAll()
        .where('id', '=', jobId)
        .executeTakeFirstOrThrow();
      expect(job).toMatchObject({ status: 'queued', error: 'gpu oom', attempts: 1, worker_id: null });
      const video = await app.db
        .selectFrom('videos')
        .select('status')
        .where('id', '=', videoId)
        .executeTakeFirstOrThrow();
      expect(video.status).toBe('analyzing');

      const again = await claim();
      expect(again.json<ClaimBody>().job).toMatchObject({ id: jobId, attempts: 2, error: null });
    });

    it('fails the job and the video for a non-retryable error', async () => {
      await clearJobs();
      const { jobId, videoId, auth } = await queuedJob();
      await claim();

      expect(
        (await post(`/internal/jobs/${jobId}/fail`, { error: 'corrupt', retryable: false })).statusCode,
      ).toBe(204);
      const job = await app.inject({ method: 'GET', url: `/v1/jobs/${jobId}`, headers: auth });
      expect(job.json<JobBody>()).toMatchObject({ status: 'failed', error: 'corrupt' });
      expect(job.json<JobBody>().finishedAt).not.toBeNull();
      const video = await app.inject({ method: 'GET', url: `/v1/videos/${videoId}`, headers: auth });
      expect(video.json<{ status: string }>().status).toBe('failed');
    });

    it('fails permanently once the third attempt fails, even if retryable', async () => {
      await clearJobs();
      const { jobId } = await queuedJob();
      for (let attempt = 1; attempt <= 3; attempt += 1) {
        const claimed = await claim();
        expect(claimed.json<ClaimBody>().job.attempts).toBe(attempt);
        await post(`/internal/jobs/${jobId}/fail`, { error: 'flaky', retryable: true });
      }
      const job = await app.db
        .selectFrom('jobs')
        .select('status')
        .where('id', '=', jobId)
        .executeTakeFirstOrThrow();
      expect(job.status).toBe('failed');
      expect((await claim()).statusCode).toBe(204);
    });

    it('rejects a failure report for a job that is not running', async () => {
      await clearJobs();
      const { jobId } = await queuedJob();
      expect((await post(`/internal/jobs/${jobId}/fail`, { error: 'x', retryable: false })).statusCode).toBe(
        409,
      );
    });
  });
});
