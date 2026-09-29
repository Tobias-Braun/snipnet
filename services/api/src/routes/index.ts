import type { FastifyPluginCallback } from 'fastify';

import { authRoutes } from './auth.js';
import { healthRoutes } from './health.js';

/**
 * Registers one module per feature under `/v1`. Later features add their file and one line here instead of
 * touching the shared application setup.
 */
export const v1Routes: FastifyPluginCallback<{ version: string }> = (app, options, done) => {
  void app.register(healthRoutes, { version: options.version });
  void app.register(authRoutes);
  done();
};
