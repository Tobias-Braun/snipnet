import { loadConfig } from './config.js';
import { createDb } from './db/client.js';
import { runMigrations } from './db/migrate.js';

/** Entry point of `pnpm migrate`: applies pending migrations and exits without starting the server. */
const config = loadConfig();
const db = createDb(config.database);
try {
  await runMigrations(db, {
    info: (obj, msg) => {
      console.log(JSON.stringify({ msg, ...obj }));
    },
  });
} finally {
  await db.destroy();
}
