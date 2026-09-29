import fastifyRateLimit from '@fastify/rate-limit';
import Fastify, { type FastifyServerOptions } from 'fastify';
import type { TypeBoxTypeProvider } from '@fastify/type-provider-typebox';

import type { AppConfig } from './config.js';
import { authPlugin } from './plugins/auth.js';
import { dbPlugin } from './plugins/db.js';
import { errorsPlugin } from './plugins/errors.js';
import { openapiPlugin } from './plugins/openapi.js';
import { generateRequestId, requestIdPlugin } from './plugins/request-id.js';
import { v1Routes } from './routes/index.js';
import { ErrorResponse } from './schemas.js';

export interface AppOptions {
  config: AppConfig;
  logger?: FastifyServerOptions['logger'];
}

/**
 * Builds the Fastify instance without binding a port, so tests can drive it through `app.inject()` and the
 * server entry point stays a thin wrapper around configuration, migrations and `listen()`.
 */
export async function buildApp(options: AppOptions) {
  const { config } = options;
  const app = Fastify({
    logger: options.logger ?? false,
    genReqId: generateRequestId,
    // Closes idle keep-alive connections on shutdown so `close()` does not wait for clients to hang up.
    forceCloseConnections: 'idle',
  }).withTypeProvider<TypeBoxTypeProvider>();

  app.addSchema(ErrorResponse);
  await app.register(requestIdPlugin);
  await app.register(errorsPlugin);
  await app.register(dbPlugin, { database: config.database });
  await app.register(authPlugin, { secret: config.secrets.jwt });
  // Opt-in: only routes that set `config.rateLimit` are limited, the rest stay unaffected.
  await app.register(fastifyRateLimit, { global: false });
  await app.register(openapiPlugin, { version: config.version });
  await app.register(v1Routes, { prefix: '/v1', version: config.version });

  return app;
}
