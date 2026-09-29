import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { buildApp } from '../src/app.js';
import type { TestSchema } from './helpers/db.js';
import {
  createMigratedTestSchema,
  insertVideo,
  PUBLIC_S3_ENDPOINT,
  withPublicS3Endpoint,
} from './helpers/seed.js';

const ADMIN = { authorization: 'Bearer test-admin-token-0123456789' };
const DAY_SECONDS = 24 * 60 * 60;

interface ExportLine {
  video: { id: string; filename: string; latestJob: unknown };
  proxyUrl: string;
  prediction: { id: string; kind: string; videoId: string; modelVersion: string | null };
  final: { id: string; kind: string; parentSetId: string | null; isFinal: boolean; createdAt: string };
}

describe('training export', () => {
  let schema: TestSchema;
  let app: Awaited<ReturnType<typeof buildApp>>;
  let counter = 0;

  beforeAll(async () => {
    schema = await createMigratedTestSchema();
    app = await buildApp({ config: withPublicS3Endpoint(schema.config) });
  });

  afterAll(async () => {
    await app.close();
    await schema.drop();
  });

  async function newUser(trainingConsent: boolean): Promise<string> {
    counter += 1;
    const user = await app.db
      .insertInto('users')
      .values({
        email: `export-user${String(counter)}@example.com`,
        password_hash: 'not-a-real-hash',
        training_consent: trainingConsent,
      })
      .returning('id')
      .executeTakeFirstOrThrow();
    return user.id;
  }

  async function newVideo(userId: string): Promise<{ videoId: string; predictionId: string }> {
    counter += 1;
    const videoId = await insertVideo(app.db, {
      user_id: userId,
      filename: `match-${String(counter)}.mov`,
      object_key: `proxies/${userId}/export-${String(counter)}.mp4`,
    });
    const predictionId = await newSet(videoId, 'prediction', null, false);
    return { videoId, predictionId };
  }

  async function newSet(
    videoId: string,
    kind: 'prediction' | 'user',
    parentSetId: string | null,
    isFinal: boolean,
  ): Promise<string> {
    const set = await app.db
      .insertInto('segment_sets')
      .values({
        video_id: videoId,
        kind,
        parent_set_id: parentSetId,
        model_version: kind === 'prediction' ? 'heuristic-1' : null,
        segments: JSON.stringify([{ startMs: 1000, endMs: 9000, label: 'rally', confidence: null }]),
        is_final: isFinal,
      })
      .returning('id')
      .executeTakeFirstOrThrow();
    return set.id;
  }

  async function exportLines(query = ''): Promise<ExportLine[]> {
    const response = await app.inject({
      method: 'GET',
      url: `/v1/admin/training-export${query}`,
      headers: ADMIN,
    });
    expect(response.statusCode).toBe(200);
    expect(response.headers['content-type']).toContain('application/x-ndjson');
    return response.body
      .split('\n')
      .filter((line) => line !== '')
      .map((line) => JSON.parse(line) as ExportLine);
  }

  it('rejects requests without the admin token', async () => {
    const url = '/v1/admin/training-export';
    expect((await app.inject({ method: 'GET', url })).statusCode).toBe(401);
    expect(
      (await app.inject({ method: 'GET', url, headers: { authorization: 'Bearer wrong-token' } })).statusCode,
    ).toBe(401);
    // A worker token is not an admin token.
    expect(
      (
        await app.inject({
          method: 'GET',
          url,
          headers: { authorization: 'Bearer test-internal-token-0123456789' },
        })
      ).statusCode,
    ).toBe(401);
  });

  it('rejects a malformed since parameter', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/admin/training-export?since=yesterday',
      headers: ADMIN,
    });
    expect(response.statusCode).toBe(400);
  });

  it('exports only consenting users, only videos with a final user set, with the ancestral prediction', async () => {
    const consenting = await newUser(true);
    const declining = await newUser(false);

    const good = await newVideo(consenting);
    // The final set descends from the prediction through an intermediate, non-final user set.
    const draft = await newSet(good.videoId, 'user', good.predictionId, false);
    const finalId = await newSet(good.videoId, 'user', draft, true);

    const notFinal = await newVideo(consenting);
    await newSet(notFinal.videoId, 'user', notFinal.predictionId, false);

    const noEdits = await newVideo(consenting);
    expect(noEdits.videoId).not.toBe('');

    const noConsent = await newVideo(declining);
    await newSet(noConsent.videoId, 'user', noConsent.predictionId, true);

    const lines = await exportLines();
    const line = lines.find((entry) => entry.video.id === good.videoId);

    expect(line).toBeDefined();
    expect(line?.final.id).toBe(finalId);
    expect(line?.final).toMatchObject({ kind: 'user', isFinal: true, parentSetId: draft });
    expect(line?.prediction).toMatchObject({
      id: good.predictionId,
      kind: 'prediction',
      videoId: good.videoId,
      modelVersion: 'heuristic-1',
    });
    expect(line?.video.latestJob).toBeNull();

    const exported = lines.map((entry) => entry.video.id);
    expect(exported).not.toContain(notFinal.videoId);
    expect(exported).not.toContain(noEdits.videoId);
    expect(exported).not.toContain(noConsent.videoId);
  });

  it('skips a video whose proxy changed after the upload was confirmed, and keeps one that did not', async () => {
    const userId = await newUser(true);
    const overwritten = await newVideo(userId);
    const intact = await newVideo(userId);
    for (const video of [overwritten, intact]) {
      await newSet(video.videoId, 'user', video.predictionId, true);
    }
    // The object store reports an ETag derived from the key; the overwritten video recorded another one.
    const recorded = { [overwritten.videoId]: '"old"', [intact.videoId]: null as string | null };
    for (const [id, etag] of Object.entries(recorded)) {
      const row = await app.db
        .selectFrom('videos')
        .select('object_key')
        .where('id', '=', id)
        .executeTakeFirstOrThrow();
      await app.db
        .updateTable('videos')
        .set({ proxy_etag: etag ?? `"etag-${row.object_key}"` })
        .where('id', '=', id)
        .execute();
    }
    const objectInfo = vi
      .spyOn(app.storage, 'objectInfo')
      .mockImplementation((key) => Promise.resolve({ sizeBytes: 2048, etag: `"etag-${key}"` }));
    try {
      const exported = (await exportLines()).map((entry) => entry.video.id);
      expect(exported).toContain(intact.videoId);
      expect(exported).not.toContain(overwritten.videoId);
    } finally {
      objectInfo.mockRestore();
    }
  });

  it('follows consent changes, so withdrawing it removes the videos from the export', async () => {
    const userId = await newUser(true);
    const video = await newVideo(userId);
    await newSet(video.videoId, 'user', video.predictionId, true);
    expect((await exportLines()).map((entry) => entry.video.id)).toContain(video.videoId);

    await app.db.updateTable('users').set({ training_consent: false }).where('id', '=', userId).execute();
    expect((await exportLines()).map((entry) => entry.video.id)).not.toContain(video.videoId);
  });

  it('exports one line per video, using the newest final set', async () => {
    const userId = await newUser(true);
    const video = await newVideo(userId);
    await newSet(video.videoId, 'user', video.predictionId, true);
    const newest = await newSet(video.videoId, 'user', video.predictionId, true);

    const lines = (await exportLines()).filter((entry) => entry.video.id === video.videoId);
    expect(lines).toHaveLength(1);
    expect(lines[0]?.final.id).toBe(newest);
  });

  it('presigns the proxy URL for 24 hours on the public endpoint', async () => {
    const userId = await newUser(true);
    const video = await newVideo(userId);
    await newSet(video.videoId, 'user', video.predictionId, true);

    const line = (await exportLines()).find((entry) => entry.video.id === video.videoId);
    const url = new URL(line?.proxyUrl ?? '');
    expect(url.origin).toBe(PUBLIC_S3_ENDPOINT);
    expect(url.pathname).toContain(`proxies/${userId}/`);
    expect(url.searchParams.get('X-Amz-Expires')).toBe(String(DAY_SECONDS));
    expect(url.searchParams.get('X-Amz-Signature')).toBeTruthy();
  });

  it('honours since and streams more videos than fit one database page', async () => {
    const userId = await newUser(true);
    const created: string[] = [];
    for (let index = 0; index < 55; index += 1) {
      const video = await newVideo(userId);
      await newSet(video.videoId, 'user', video.predictionId, true);
      created.push(video.videoId);
    }

    const all = await exportLines();
    const exported = new Set(all.map((entry) => entry.video.id));
    for (const id of created) expect(exported.has(id)).toBe(true);
    expect(exported.size).toBe(all.length);

    const cutoff = all[all.length - 5]?.final.createdAt ?? '';
    const recent = await exportLines(`?since=${encodeURIComponent(cutoff)}`);
    expect(recent.length).toBeGreaterThanOrEqual(5);
    expect(recent.length).toBeLessThan(all.length);
    for (const entry of recent) {
      expect(new Date(entry.final.createdAt).getTime()).toBeGreaterThanOrEqual(new Date(cutoff).getTime());
    }

    expect(await exportLines('?since=2999-01-01T00:00:00Z')).toEqual([]);
  });
});
