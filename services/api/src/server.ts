import { buildApp } from './app.js';
import { loadConfig } from './config.js';
import { createDb } from './db/client.js';
import { runMigrations } from './db/migrate.js';

/** Upper bound for finishing in-flight requests on SIGTERM, below Docker's default 10 s stop grace period. */
const SHUTDOWN_TIMEOUT_MS = 8000;

const config = loadConfig();

// Migrations use their own short-lived pool so the app's pool only exists once the schema is up to date.
const migrationDb = createDb(config.database);
try {
  await runMigrations(migrationDb, {
    info: (obj, msg) => {
      console.log(JSON.stringify({ msg, ...obj }));
    },
  });
} finally {
  await migrationDb.destroy();
}

const app = await buildApp({
  config,
  logger: { level: config.logLevel, redact: ['req.headers.authorization'] },
});

let shuttingDown = false;
for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    if (shuttingDown) return;
    shuttingDown = true;
    app.log.info({ signal }, 'shutting down');
    const timer = setTimeout(() => {
      app.log.error('graceful shutdown timed out, exiting');
      process.exit(1);
    }, SHUTDOWN_TIMEOUT_MS);
    timer.unref();
    // close() stops accepting connections, drains in-flight requests and runs the onClose hooks (DB pool).
    app.close().then(
      () => process.exit(0),
      (error: unknown) => {
        app.log.error({ err: error }, 'error during shutdown');
        process.exit(1);
      },
    );
  });
}

await app.listen({ host: config.host, port: config.port });
