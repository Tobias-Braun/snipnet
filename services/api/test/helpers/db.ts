import { randomBytes } from 'node:crypto';

import pg from 'pg';

import { type AppConfig, loadConfig } from '../../src/config.js';

/**
 * Connection settings of the Postgres used by integration tests: the standard libpq variables, defaulting to
 * the user and database of the CI service container. PGPASSWORD has no default: locally, export the variables of
 * a running Postgres (`set -a; . infra/.env; set +a` for the compose stack).
 */
function baseEnv(): NodeJS.ProcessEnv {
  return {
    PGHOST: process.env['PGHOST'] ?? 'localhost',
    PGPORT: process.env['PGPORT'] ?? '5432',
    PGUSER: process.env['PGUSER'] ?? 'snipnet',
    PGPASSWORD: process.env['PGPASSWORD'],
    PGDATABASE: process.env['PGDATABASE'] ?? 'snipnet',
    S3_ENDPOINT: process.env['S3_ENDPOINT'] ?? 'http://localhost:9000',
    S3_PUBLIC_ENDPOINT: process.env['S3_PUBLIC_ENDPOINT'],
    S3_REGION: process.env['S3_REGION'],
    S3_BUCKET: process.env['S3_BUCKET'] ?? 'snipnet-test',
    S3_ACCESS_KEY: process.env['S3_ACCESS_KEY'] ?? 'snipnet',
    S3_SECRET_KEY: process.env['S3_SECRET_KEY'] ?? 'unused-without-minio',
    JWT_SECRET: 'test-jwt-secret-0123456789',
    INTERNAL_TOKEN: 'test-internal-token-0123456789',
    ADMIN_TOKEN: 'test-admin-token-0123456789',
    LOG_LEVEL: 'silent',
    // Test files share one app across many registrations; the limit itself is tested in auth.test.ts.
    REGISTER_RATE_LIMIT_MAX: '1000',
  };
}

export interface TestSchema {
  /** App configuration whose connections are confined to the fresh schema. */
  config: AppConfig;
  name: string;
  /** Drops the schema with everything in it. */
  drop: () => Promise<void>;
}

/**
 * Creates an empty, randomly named schema so every test file (and every run against a shared database) works on
 * its own tables. Migrations are applied by the code under test, not here.
 */
export async function createTestSchema(): Promise<TestSchema> {
  const name = `test_${randomBytes(6).toString('hex')}`;
  const config = loadConfig({ ...baseEnv(), PGSCHEMA: name });
  const { host, port, user, password, database } = config.database;

  const admin = new pg.Client({ host, port, user, password, database });
  await admin.connect();
  try {
    await admin.query(`CREATE SCHEMA ${name}`);
  } finally {
    await admin.end();
  }

  return {
    config,
    name,
    drop: async () => {
      const client = new pg.Client({ host, port, user, password, database });
      await client.connect();
      try {
        await client.query(`DROP SCHEMA ${name} CASCADE`);
      } finally {
        await client.end();
      }
    },
  };
}
