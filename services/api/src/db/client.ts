import { Kysely, PostgresDialect } from 'kysely';
import pg from 'pg';

import type { DatabaseConfig } from '../config.js';
import type { Database } from './types.js';

const PG_TYPE_INT8 = 20;

/**
 * Creates the connection pool and the Kysely instance on top of it.
 *
 * `bigint` columns (only `proxy_size_bytes`) are parsed to numbers, since node-postgres returns them as strings
 * by default and the values stay far below 2^53. When `schema` is set it becomes the first `search_path` entry
 * of every connection, so migrations and queries operate inside that schema without qualifying table names.
 */
export function createDb(config: DatabaseConfig): Kysely<Database> {
  const types = new pg.TypeOverrides();
  types.setTypeParser(PG_TYPE_INT8, Number);

  const pool = new pg.Pool({
    host: config.host,
    port: config.port,
    user: config.user,
    password: config.password,
    database: config.database,
    ...(config.schema === undefined ? {} : { options: `-c search_path=${config.schema}` }),
    types,
  });

  // Without a listener, an error on an idle client (e.g. Postgres restarting) would crash the process.
  pool.on('error', () => undefined);

  return new Kysely<Database>({ dialect: new PostgresDialect({ pool }) });
}
