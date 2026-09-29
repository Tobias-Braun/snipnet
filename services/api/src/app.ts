import Fastify, { type FastifyInstance, type FastifyServerOptions } from 'fastify';

import { healthRoutes } from './routes/health.js';

export interface AppOptions {
  /** Reported by `GET /v1/health`; the server passes the package version. */
  version: string;
  logger?: FastifyServerOptions['logger'];
}

/**
 * Builds the Fastify instance without binding a port, so tests can drive it through `app.inject()` and the
 * server entry point stays a thin wrapper around configuration and `listen()`.
 */
export async function buildApp(options: AppOptions): Promise<FastifyInstance> {
  const app = Fastify({ logger: options.logger ?? false });

  await app.register(healthRoutes, { prefix: '/v1', version: options.version });

  return app;
}
