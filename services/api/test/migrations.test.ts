import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql, type Kysely } from 'kysely';

import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import type { Database } from '../src/db/types.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';

describe('migrations', () => {
  let schema: TestSchema;
  let db: Kysely<Database>;

  beforeAll(async () => {
    schema = await createTestSchema();
    db = createDb(schema.config.database);
    await runMigrations(db);
  });

  afterAll(async () => {
    await db.destroy();
    await schema.drop();
  });

  it('creates the base tables inside the isolated schema', async () => {
    const { rows } = await sql<{ table_name: string }>`
      SELECT table_name FROM information_schema.tables WHERE table_schema = ${schema.name}
    `.execute(db);

    expect(rows.map((row) => row.table_name)).toEqual(
      expect.arrayContaining(['users', 'videos', 'jobs', 'segment_sets', 'waitlist']),
    );
  });

  it('is idempotent', async () => {
    await expect(runMigrations(db)).resolves.toBeUndefined();
  });

  it('applies defaults and reads timestamps and bigints back in usable types', async () => {
    const user = await db
      .insertInto('users')
      .values({ email: 'a@example.com', password_hash: 'x' })
      .returningAll()
      .executeTakeFirstOrThrow();
    expect(user.training_consent).toBe(false);
    expect(user.created_at).toBeInstanceOf(Date);

    const video = await db
      .insertInto('videos')
      .values({
        user_id: user.id,
        filename: 'match.mp4',
        duration_ms: 60_000,
        width: 854,
        height: 480,
        fps: 15,
        proxy_size_bytes: 5_000_000_000,
        object_key: 'proxies/1.mp4',
      })
      .returningAll()
      .executeTakeFirstOrThrow();
    expect(video.status).toBe('created');
    expect(video.proxy_size_bytes).toBe(5_000_000_000);
  });

  it('compares user emails case-insensitively', async () => {
    await expect(
      db.insertInto('users').values({ email: 'A@EXAMPLE.COM', password_hash: 'x' }).execute(),
    ).rejects.toThrow(/users_email_lower_key/);
  });

  it('allows only one queued or running job per video', async () => {
    const user = await db
      .insertInto('users')
      .values({ email: 'jobs@example.com', password_hash: 'x' })
      .returning('id')
      .executeTakeFirstOrThrow();
    const video = await db
      .insertInto('videos')
      .values({
        user_id: user.id,
        filename: 'v.mp4',
        duration_ms: 1000,
        width: 10,
        height: 10,
        fps: 15,
        proxy_size_bytes: 1,
        object_key: 'k',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    const first = await db
      .insertInto('jobs')
      .values({ video_id: video.id })
      .returning('id')
      .executeTakeFirstOrThrow();
    await expect(db.insertInto('jobs').values({ video_id: video.id }).execute()).rejects.toThrow(
      /jobs_one_active_per_video_key/,
    );

    await db.updateTable('jobs').set({ status: 'failed' }).where('id', '=', first.id).execute();
    await expect(db.insertInto('jobs').values({ video_id: video.id }).execute()).resolves.toBeDefined();
  });

  it('deletes dependent rows together with a video', async () => {
    const user = await db
      .insertInto('users')
      .values({ email: 'cascade@example.com', password_hash: 'x' })
      .returning('id')
      .executeTakeFirstOrThrow();
    const video = await db
      .insertInto('videos')
      .values({
        user_id: user.id,
        filename: 'v.mp4',
        duration_ms: 1000,
        width: 10,
        height: 10,
        fps: 15,
        proxy_size_bytes: 1,
        object_key: 'k2',
      })
      .returning('id')
      .executeTakeFirstOrThrow();
    await db
      .insertInto('segment_sets')
      .values({ video_id: video.id, kind: 'user', segments: JSON.stringify([{ startMs: 0, endMs: 10 }]) })
      .execute();

    await db.deleteFrom('videos').where('id', '=', video.id).execute();

    const remaining = await db
      .selectFrom('segment_sets')
      .select('id')
      .where('video_id', '=', video.id)
      .execute();
    expect(remaining).toEqual([]);
  });
});
