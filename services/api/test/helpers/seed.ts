import type { Insertable, Kysely } from 'kysely';

import { createDb } from '../../src/db/client.js';
import { runMigrations } from '../../src/db/migrate.js';
import type { Database, VideosTable } from '../../src/db/types.js';
import { createTestSchema, type TestSchema } from './db.js';

/** Duration of every video inserted by `insertVideo` unless a test overrides it. */
export const SEED_DURATION_MS = 90_000;

/**
 * Creates a fresh test schema and applies all migrations to it with a short-lived connection, so the app a test
 * builds afterwards starts on complete tables.
 */
export async function createMigratedTestSchema(): Promise<TestSchema> {
  const schema = await createTestSchema();
  const db = createDb(schema.config.database);
  try {
    await runMigrations(db);
  } finally {
    await db.destroy();
  }
  return schema;
}

/**
 * Inserts a video row directly, so route tests depend neither on the upload flow and its object store nor on the
 * worker. Unless overridden, the row is an analyzed 854x480, 15 fps proxy of `SEED_DURATION_MS`.
 */
export async function insertVideo(
  db: Kysely<Database>,
  values: Pick<Insertable<VideosTable>, 'user_id' | 'object_key'> & Partial<Insertable<VideosTable>>,
): Promise<string> {
  const row = await db
    .insertInto('videos')
    .values({
      filename: 'match.mov',
      duration_ms: SEED_DURATION_MS,
      width: 854,
      height: 480,
      fps: 15,
      proxy_size_bytes: 2048,
      status: 'analyzed',
      ...values,
    })
    .returning('id')
    .executeTakeFirstOrThrow();
  return row.id;
}
