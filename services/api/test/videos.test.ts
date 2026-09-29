import { HeadObjectCommand, PutObjectCommand } from '@aws-sdk/client-s3';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { buildApp } from '../src/app.js';
import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';
import { createTestBucket, type TestBucket } from './helpers/s3.js';

interface UploadInfo {
  url: string;
  method: string;
  headers: Record<string, string>;
  expiresAt: string;
}

interface VideoBody {
  id: string;
  filename: string;
  durationMs: number;
  width: number;
  height: number;
  fps: number;
  proxySizeBytes: number;
  status: string;
  court: unknown;
  createdAt: string;
  updatedAt: string;
  latestJob: unknown;
}

const META = { filename: 'match.mov', durationMs: 90_000, width: 854, height: 480, fps: 15 };
const COURT = { roi: { x: 0.1, y: 0.2, width: 0.6, height: 0.7 }, netPoint: { x: 0.4, y: 0.55 } };

describe('video routes', () => {
  let schema: TestSchema;
  let bucket: TestBucket;
  let app: Awaited<ReturnType<typeof buildApp>>;
  let counter = 0;

  beforeAll(async () => {
    schema = await createTestSchema();
    bucket = await createTestBucket(schema.config.s3);
    const db = createDb(schema.config.database);
    try {
      await runMigrations(db);
    } finally {
      await db.destroy();
    }
    app = await buildApp({ config: { ...schema.config, s3: bucket.config } });
  });

  afterAll(async () => {
    await app.close();
    await bucket.drop();
    await schema.drop();
  });

  async function newUser(): Promise<{ token: string; id: string; auth: { authorization: string } }> {
    counter += 1;
    const response = await app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: `video-user${String(counter)}@example.com`, password: 'correct horse' },
    });
    const body = response.json<{ token: string; user: { id: string } }>();
    return { token: body.token, id: body.user.id, auth: { authorization: `Bearer ${body.token}` } };
  }

  async function createVideo(auth: { authorization: string }, proxySizeBytes = 2048) {
    const response = await app.inject({
      method: 'POST',
      url: '/v1/videos',
      headers: auth,
      payload: { ...META, proxySizeBytes },
    });
    expect(response.statusCode).toBe(201);
    return response.json<{ video: VideoBody; upload: UploadInfo }>();
  }

  /** Uploads like the desktop client will: a plain HTTP PUT to the presigned URL with the returned headers. */
  async function uploadProxy(upload: UploadInfo, body: Uint8Array, headers = upload.headers) {
    return fetch(upload.url, { method: upload.method, headers, body });
  }

  async function objectExists(key: string): Promise<boolean> {
    try {
      await bucket.client.send(new HeadObjectCommand({ Bucket: bucket.config.bucket, Key: key }));
      return true;
    } catch {
      return false;
    }
  }

  describe('POST /v1/videos', () => {
    it('creates the video and returns a presigned PUT for proxies/<userId>/<videoId>.mp4', async () => {
      const user = await newUser();
      const before = Date.now();
      const { video, upload } = await createVideo(user.auth, 4096);

      expect(video).toEqual({
        id: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
        ...META,
        proxySizeBytes: 4096,
        status: 'created',
        court: null,
        createdAt: expect.stringMatching(/^\d{4}-\d\d-\d\dT.*Z$/) as string,
        updatedAt: expect.stringMatching(/^\d{4}-\d\d-\d\dT.*Z$/) as string,
        latestJob: null,
      });
      expect(upload.method).toBe('PUT');
      expect(upload.headers).toEqual({ 'Content-Type': 'video/mp4' });
      const url = new URL(upload.url);
      expect(url.pathname).toBe(`/${bucket.config.bucket}/proxies/${user.id}/${video.id}.mp4`);
      expect(url.searchParams.get('X-Amz-Expires')).toBe('3600');
      const expiresIn = new Date(upload.expiresAt).getTime() - before;
      expect(expiresIn).toBeGreaterThan(3_590_000);
      expect(expiresIn).toBeLessThan(3_610_000);
    });

    it('signs the URL for the public endpoint', async () => {
      const publicApp = await buildApp({
        config: {
          ...schema.config,
          s3: { ...bucket.config, publicEndpoint: 'https://storage.snipnet.test' },
        },
      });
      try {
        const user = await newUser();
        const response = await publicApp.inject({
          method: 'POST',
          url: '/v1/videos',
          headers: user.auth,
          payload: { ...META, proxySizeBytes: 10 },
        });

        expect(response.json<{ upload: UploadInfo }>().upload.url).toMatch(
          /^https:\/\/storage\.snipnet\.test\/.+X-Amz-Signature=/,
        );
      } finally {
        await publicApp.close();
      }
    });

    it('requires a token', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/v1/videos',
        payload: { ...META, proxySizeBytes: 1 },
      });

      expect(response.statusCode).toBe(401);
    });

    it.each([
      ['a missing filename', { ...META, filename: undefined, proxySizeBytes: 10 }],
      ['an empty filename', { ...META, filename: '', proxySizeBytes: 10 }],
      ['a zero duration', { ...META, durationMs: 0, proxySizeBytes: 10 }],
      ['a fractional duration', { ...META, durationMs: 1.5, proxySizeBytes: 10 }],
      ['a zero width', { ...META, width: 0, proxySizeBytes: 10 }],
      ['a negative height', { ...META, height: -1, proxySizeBytes: 10 }],
      ['a zero fps', { ...META, fps: 0, proxySizeBytes: 10 }],
      ['a zero proxy size', { ...META, proxySizeBytes: 0 }],
      ['a non-numeric proxy size', { ...META, proxySizeBytes: 'big' }],
      ['a missing proxy size', { ...META }],
    ])('rejects %s with validation_error', async (_name, payload) => {
      const user = await newUser();
      const response = await app.inject({ method: 'POST', url: '/v1/videos', headers: user.auth, payload });

      expect(response.statusCode).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    });
  });

  describe('upload to MinIO and POST /v1/videos/:id/upload-complete', () => {
    it('accepts a real upload through the presigned URL and marks the video uploaded', async () => {
      const user = await newUser();
      const { video, upload } = await createVideo(user.auth, 2048);

      const put = await uploadProxy(upload, new Uint8Array(2048).fill(7));
      expect(put.status).toBe(200);

      const response = await app.inject({
        method: 'POST',
        url: `/v1/videos/${video.id}/upload-complete`,
        headers: user.auth,
      });

      expect(response.statusCode).toBe(200);
      expect(response.json<VideoBody>()).toMatchObject({ id: video.id, status: 'uploaded' });
      expect(await objectExists(`proxies/${user.id}/${video.id}.mp4`)).toBe(true);
    });

    it('is idempotent and keeps a later status', async () => {
      const user = await newUser();
      const { video, upload } = await createVideo(user.auth, 16);
      await uploadProxy(upload, new Uint8Array(16));
      const complete = () =>
        app.inject({ method: 'POST', url: `/v1/videos/${video.id}/upload-complete`, headers: user.auth });
      await complete();
      await app.db.updateTable('videos').set({ status: 'analyzed' }).where('id', '=', video.id).execute();

      const again = await complete();

      expect(again.statusCode).toBe(200);
      expect(again.json<VideoBody>().status).toBe('analyzed');
    });

    it('answers 409 when nothing was uploaded', async () => {
      const user = await newUser();
      const { video } = await createVideo(user.auth);

      const response = await app.inject({
        method: 'POST',
        url: `/v1/videos/${video.id}/upload-complete`,
        headers: user.auth,
      });

      expect(response.statusCode).toBe(409);
      expect(response.json()).toMatchObject({ error: { code: 'conflict' } });
      const after = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: user.auth });
      expect(after.json<VideoBody>().status).toBe('created');
    });

    it('answers 409 when the stored size differs from proxySizeBytes', async () => {
      const user = await newUser();
      const { video } = await createVideo(user.auth, 100);
      // Written straight to the bucket, because the presigned URL itself refuses a body of another size.
      await bucket.client.send(
        new PutObjectCommand({
          Bucket: bucket.config.bucket,
          Key: `proxies/${user.id}/${video.id}.mp4`,
          Body: new Uint8Array(99),
        }),
      );

      const response = await app.inject({
        method: 'POST',
        url: `/v1/videos/${video.id}/upload-complete`,
        headers: user.auth,
      });

      expect(response.statusCode).toBe(409);
      expect(response.json()).toMatchObject({
        error: { code: 'conflict', message: expect.stringContaining('99') as string },
      });
    });

    it('refuses an upload with another content type', async () => {
      const user = await newUser();
      const { upload } = await createVideo(user.auth, 32);

      const put = await uploadProxy(upload, new Uint8Array(32), { 'Content-Type': 'text/plain' });

      expect(put.status).toBe(403);
    });

    it('refuses an upload whose size differs from the announced one', async () => {
      const user = await newUser();
      const { upload } = await createVideo(user.auth, 32);

      const put = await uploadProxy(upload, new Uint8Array(64));

      expect(put.status).toBe(403);
    });

    it('answers 404 for a video of another user', async () => {
      const owner = await newUser();
      const other = await newUser();
      const { video } = await createVideo(owner.auth);

      const response = await app.inject({
        method: 'POST',
        url: `/v1/videos/${video.id}/upload-complete`,
        headers: other.auth,
      });

      expect(response.statusCode).toBe(404);
    });
  });

  describe('GET /v1/videos and GET /v1/videos/:id', () => {
    it('lists only the own videos, newest first', async () => {
      const user = await newUser();
      const other = await newUser();
      const first = await createVideo(user.auth);
      const second = await createVideo(user.auth);
      await createVideo(other.auth);

      const response = await app.inject({ method: 'GET', url: '/v1/videos', headers: user.auth });

      expect(response.statusCode).toBe(200);
      expect(response.json<{ items: VideoBody[] }>().items.map((item) => item.id)).toEqual([
        second.video.id,
        first.video.id,
      ]);
    });

    it('returns an empty list for a user without videos', async () => {
      const user = await newUser();

      const response = await app.inject({ method: 'GET', url: '/v1/videos', headers: user.auth });

      expect(response.json()).toEqual({ items: [] });
    });

    it('includes the latest job of each video', async () => {
      const user = await newUser();
      const { video } = await createVideo(user.auth);
      await app.db.insertInto('jobs').values({ video_id: video.id, status: 'failed', attempts: 3 }).execute();
      const newest = await app.db
        .insertInto('jobs')
        .values({ video_id: video.id, status: 'running', progress: 0.25, attempts: 1 })
        .returning('id')
        .executeTakeFirstOrThrow();

      const single = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: user.auth });
      const list = await app.inject({ method: 'GET', url: '/v1/videos', headers: user.auth });

      const expected = {
        id: newest.id,
        videoId: video.id,
        status: 'running',
        progress: 0.25,
        modelVersion: null,
        error: null,
        attempts: 1,
        createdAt: expect.any(String) as string,
        startedAt: null,
        finishedAt: null,
      };
      expect(single.json<VideoBody>().latestJob).toEqual(expected);
      expect(list.json<{ items: VideoBody[] }>().items[0]?.latestJob).toEqual(expected);
    });

    it('answers 404 for videos of other users, unknown ids and malformed ids alike', async () => {
      const owner = await newUser();
      const other = await newUser();
      const { video } = await createVideo(owner.auth);

      const foreign = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: other.auth });
      const unknown = await app.inject({
        method: 'GET',
        url: '/v1/videos/00000000-0000-4000-8000-000000000000',
        headers: other.auth,
      });
      const malformed = await app.inject({
        method: 'GET',
        url: '/v1/videos/not-a-uuid',
        headers: other.auth,
      });

      for (const response of [foreign, unknown, malformed]) {
        expect(response.statusCode).toBe(404);
        expect(response.json()).toMatchObject({ error: { code: 'not_found' } });
      }
    });

    it('requires a token', async () => {
      expect((await app.inject({ method: 'GET', url: '/v1/videos' })).statusCode).toBe(401);
      expect((await app.inject({ method: 'GET', url: `/v1/videos/${crypto.randomUUID()}` })).statusCode).toBe(
        401,
      );
    });
  });

  describe('PUT /v1/videos/:id/court', () => {
    it('stores the court and returns the video', async () => {
      const user = await newUser();
      const { video } = await createVideo(user.auth);

      const response = await app.inject({
        method: 'PUT',
        url: `/v1/videos/${video.id}/court`,
        headers: user.auth,
        payload: COURT,
      });

      expect(response.statusCode).toBe(200);
      expect(response.json<VideoBody>().court).toEqual(COURT);
      const fetched = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: user.auth });
      expect(fetched.json<VideoBody>().court).toEqual(COURT);
    });

    it('replaces an earlier court and accepts a ROI that fills the whole frame', async () => {
      const user = await newUser();
      const { video } = await createVideo(user.auth);
      const put = (payload: object) =>
        app.inject({ method: 'PUT', url: `/v1/videos/${video.id}/court`, headers: user.auth, payload });
      await put(COURT);

      const full = { roi: { x: 0, y: 0, width: 1, height: 1 }, netPoint: { x: 0, y: 1 } };
      const response = await put(full);

      expect(response.statusCode).toBe(200);
      expect(response.json<VideoBody>().court).toEqual(full);
    });

    it('tolerates floating point rounding at the frame edge', async () => {
      const user = await newUser();
      const { video } = await createVideo(user.auth);

      const response = await app.inject({
        method: 'PUT',
        url: `/v1/videos/${video.id}/court`,
        headers: user.auth,
        payload: { roi: { x: 0.7, y: 0.1, width: 0.3, height: 0.9 }, netPoint: { x: 0.5, y: 0.5 } },
      });

      expect(response.statusCode).toBe(200);
    });

    it.each([
      ['a negative x', { ...COURT, roi: { ...COURT.roi, x: -0.1 } }],
      ['a y above 1', { ...COURT, roi: { ...COURT.roi, y: 1.1 } }],
      ['a zero width', { ...COURT, roi: { ...COURT.roi, width: 0 } }],
      ['a negative height', { ...COURT, roi: { ...COURT.roi, height: -0.2 } }],
      [
        'a ROI that leaves the frame to the right',
        { ...COURT, roi: { x: 0.5, y: 0, width: 0.6, height: 0.5 } },
      ],
      [
        'a ROI that leaves the frame at the bottom',
        { ...COURT, roi: { x: 0, y: 0.5, width: 0.5, height: 0.6 } },
      ],
      ['a net point x above 1', { ...COURT, netPoint: { x: 1.5, y: 0.5 } }],
      ['a negative net point y', { ...COURT, netPoint: { x: 0.5, y: -0.01 } }],
      ['a missing net point', { roi: COURT.roi }],
      ['a missing roi', { netPoint: COURT.netPoint }],
      ['non-numeric coordinates', { ...COURT, netPoint: { x: 'left', y: 'top' } }],
    ])('rejects %s with validation_error', async (_name, payload) => {
      const user = await newUser();
      const { video } = await createVideo(user.auth);

      const response = await app.inject({
        method: 'PUT',
        url: `/v1/videos/${video.id}/court`,
        headers: user.auth,
        payload,
      });

      expect(response.statusCode).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
      const fetched = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: user.auth });
      expect(fetched.json<VideoBody>().court).toBeNull();
    });

    it('answers 404 for a video of another user and leaves it untouched', async () => {
      const owner = await newUser();
      const other = await newUser();
      const { video } = await createVideo(owner.auth);

      const response = await app.inject({
        method: 'PUT',
        url: `/v1/videos/${video.id}/court`,
        headers: other.auth,
        payload: COURT,
      });

      expect(response.statusCode).toBe(404);
      const fetched = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: owner.auth });
      expect(fetched.json<VideoBody>().court).toBeNull();
    });
  });

  describe('DELETE /v1/videos/:id', () => {
    it('removes the proxy object, the video and its jobs', async () => {
      const user = await newUser();
      const { video, upload } = await createVideo(user.auth, 8);
      await uploadProxy(upload, new Uint8Array(8));
      await app.db.insertInto('jobs').values({ video_id: video.id }).execute();
      const key = `proxies/${user.id}/${video.id}.mp4`;
      expect(await objectExists(key)).toBe(true);

      const response = await app.inject({
        method: 'DELETE',
        url: `/v1/videos/${video.id}`,
        headers: user.auth,
      });

      expect(response.statusCode).toBe(204);
      expect(response.body).toBe('');
      expect(await objectExists(key)).toBe(false);
      const fetched = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: user.auth });
      expect(fetched.statusCode).toBe(404);
      const jobs = await app.db.selectFrom('jobs').select('id').where('video_id', '=', video.id).execute();
      expect(jobs).toEqual([]);
    });

    it('works for a video that was never uploaded', async () => {
      const user = await newUser();
      const { video } = await createVideo(user.auth);

      const response = await app.inject({
        method: 'DELETE',
        url: `/v1/videos/${video.id}`,
        headers: user.auth,
      });

      expect(response.statusCode).toBe(204);
    });

    it('answers 404 for a video of another user and keeps its object', async () => {
      const owner = await newUser();
      const other = await newUser();
      const { video, upload } = await createVideo(owner.auth, 8);
      await uploadProxy(upload, new Uint8Array(8));

      const response = await app.inject({
        method: 'DELETE',
        url: `/v1/videos/${video.id}`,
        headers: other.auth,
      });

      expect(response.statusCode).toBe(404);
      expect(await objectExists(`proxies/${owner.id}/${video.id}.mp4`)).toBe(true);
      const fetched = await app.inject({ method: 'GET', url: `/v1/videos/${video.id}`, headers: owner.auth });
      expect(fetched.statusCode).toBe(200);
    });
  });
});
