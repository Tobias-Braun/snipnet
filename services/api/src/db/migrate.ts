import { readdir, readFile } from 'node:fs/promises';

import { type Kysely, sql } from 'kysely';
import { type Migration, type MigrationProvider, Migrator } from 'kysely/migration';

import type { Database } from './types.js';

const MIGRATIONS_DIR = new URL('../../migrations/', import.meta.url);

/**
 * Serves the plain `.sql` files of `services/api/migrations`, applied in file name order. The directory sits
 * two levels above both `src/db` (tsx) and `dist/db` (compiled build), like `package.json`.
 */
const sqlFileProvider: MigrationProvider = {
  async getMigrations() {
    const migrations: Record<string, Migration> = {};
    const files = (await readdir(MIGRATIONS_DIR)).filter((file) => file.endsWith('.sql'));
    for (const file of files) {
      const text = await readFile(new URL(file, MIGRATIONS_DIR), 'utf8');
      migrations[file.slice(0, -'.sql'.length)] = {
        // No parameters are passed, so pg uses the simple query protocol and accepts several statements.
        up: async (db) => {
          await sql.raw(text).execute(db);
        },
      };
    }
    return migrations;
  },
};

export interface MigrationLogger {
  info: (obj: object, msg: string) => void;
}

/**
 * Applies all pending migrations, each in its own transaction. Kysely serializes concurrent runners with a
 * lock, so several API instances starting at once is safe. Throws when a migration fails, which aborts startup.
 */
export async function runMigrations(db: Kysely<Database>, logger?: MigrationLogger): Promise<void> {
  // Kysely finds its bookkeeping tables by name across all schemas unless told which one to use, so a migration
  // table of another schema (parallel test files each work in their own schema) would be mistaken for ours.
  const { rows } = await sql<{ schema: string }>`select current_schema() as schema`.execute(db);
  const schema = rows[0]?.schema;
  const migrator = new Migrator({
    db,
    provider: sqlFileProvider,
    ...(schema === undefined ? {} : { migrationTableSchema: schema }),
  });
  const { error, results } = await migrator.migrateToLatest();

  for (const result of results ?? []) {
    logger?.info({ migration: result.migrationName, status: result.status }, 'migration');
  }
  if (error) {
    throw error instanceof Error ? error : new Error(`Migration failed: ${JSON.stringify(error)}`);
  }
}
