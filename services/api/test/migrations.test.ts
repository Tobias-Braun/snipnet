import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { readFile } from 'node:fs/promises';

import { sql, type Insertable, type Kysely } from 'kysely';

import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import type { Database, VideosTable } from '../src/db/types.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';
import { insertVideo } from './helpers/seed.js';

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

  /** Inserts a user owning one minimal video; `name` keeps email and object key unique across tests. */
  async function insertUserVideo(name: string): Promise<{ id: string }> {
    const user = await db
      .insertInto('users')
      .values({ email: `${name}@example.com`, password_hash: 'x' })
      .returning('id')
      .executeTakeFirstOrThrow();
    const id = await insertVideo(db, {
      user_id: user.id,
      object_key: `proxies/${name}.mp4`,
      status: 'created',
    });
    return { id };
  }

  it('allows only one queued or running job per video', async () => {
    const video = await insertUserVideo('jobs');

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

  it('backfills court detection tasks for videos that predate the task table', async () => {
    const user = await db
      .insertInto('users')
      .values({ email: 'backfill@example.com', password_hash: 'x' })
      .returning('id')
      .executeTakeFirstOrThrow();
    const seed = (name: string, values: Partial<Insertable<VideosTable>>) =>
      insertVideo(db, { user_id: user.id, object_key: `proxies/backfill-${name}.mp4`, ...values });

    const uploaded = await seed('uploaded', { status: 'uploaded' });
    const failed = await seed('failed', { status: 'failed' });
    const created = await seed('created', { status: 'created' });
    const confirmed = await seed('confirmed', {
      status: 'analyzed',
      court: JSON.stringify({ roi: { x: 0, y: 0, width: 1, height: 1 }, netPoint: { x: 0.5, y: 0.5 } }),
    });
    const alreadyDone = await seed('done', { status: 'analyzed' });
    await db
      .insertInto('court_detection_tasks')
      .values({ video_id: alreadyDone, status: 'succeeded', attempts: 2 })
      .execute();

    const migration = await readFile(
      new URL('../migrations/004_backfill_court_detection_tasks.sql', import.meta.url),
      'utf8',
    );
    // The migration already ran on the empty schema, so run its statement again against the seeded rows.
    await sql.raw(migration).execute(db);

    const tasks = await db
      .selectFrom('court_detection_tasks')
      .select(['video_id', 'status', 'attempts'])
      .execute();
    const byVideo = new Map(tasks.map((task) => [task.video_id, task]));
    expect(byVideo.get(uploaded)).toMatchObject({ status: 'queued', attempts: 0 });
    expect(byVideo.get(failed)).toMatchObject({ status: 'queued' });
    expect(byVideo.has(created)).toBe(false);
    expect(byVideo.has(confirmed)).toBe(false);
    expect(byVideo.get(alreadyDone)).toMatchObject({ status: 'succeeded', attempts: 2 });
  });

  it('deletes dependent rows together with a video', async () => {
    const video = await insertUserVideo('cascade');
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
