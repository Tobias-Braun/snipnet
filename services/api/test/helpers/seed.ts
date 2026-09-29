import type { FastifyInstance } from 'fastify';
import type { Insertable, Kysely } from 'kysely';

import type { AppConfig } from '../../src/config.js';
import { createDb } from '../../src/db/client.js';
import { runMigrations } from '../../src/db/migrate.js';
import type { Database, VideosTable } from '../../src/db/types.js';
import { createTestSchema, type TestSchema } from './db.js';

/**
 * Public S3 endpoint that differs from the internal one, so tests can tell which of the two a presigned URL was
 * signed for: clients and exports get the public endpoint, the worker the internal one.
 */
export const PUBLIC_S3_ENDPOINT = 'http://public-s3.example.test:9000';

/** Returns `config` with its S3 public endpoint set to `PUBLIC_S3_ENDPOINT`. */
export function withPublicS3Endpoint(config: AppConfig): AppConfig {
  return { ...config, s3: { ...config.s3, publicEndpoint: PUBLIC_S3_ENDPOINT } };
}

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

/** Registers a user with `email` through the public endpoint and returns its id and bearer header. */
export async function registerUser(
  app: FastifyInstance,
  email: string,
): Promise<{ id: string; auth: { authorization: string } }> {
  const response = await app.inject({
    method: 'POST',
    url: '/v1/auth/register',
    payload: { email, password: 'correct horse' },
  });
  const body = response.json<{ token: string; user: { id: string } }>();
  return { id: body.user.id, auth: { authorization: `Bearer ${body.token}` } };
}
