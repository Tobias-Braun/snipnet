import type { Kysely } from 'kysely';
import fp from 'fastify-plugin';

import type { DatabaseConfig } from '../config.js';
import { createDb } from '../db/client.js';
import type { Database } from '../db/types.js';

declare module 'fastify' {
  interface FastifyInstance {
    db: Kysely<Database>;
  }
}

/** Decorates the app with `app.db` and closes the pool when the app closes, which the graceful shutdown relies on. */
export const dbPlugin = fp<{ database: DatabaseConfig }>(
  (app, options, done) => {
    const db = createDb(options.database);
    app.decorate('db', db);
    app.addHook('onClose', async () => {
      await db.destroy();
    });
    done();
  },
  { name: 'db' },
);
