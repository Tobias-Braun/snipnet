import type { FastifyPluginCallback } from 'fastify';

import { adminRoutes } from './admin.js';
import { authRoutes, type AuthRouteOptions } from './auth.js';
import { healthRoutes } from './health.js';
import { jobRoutes } from './jobs.js';
import { segmentSetRoutes } from './segment-sets.js';
import { videoRoutes } from './videos.js';
import { waitlistRoutes, type WaitlistRouteOptions } from './waitlist.js';

/**
 * Registers one module per feature under `/v1`. Later features add their file and one line here instead of
 * touching the shared application setup.
 */
export const v1Routes: FastifyPluginCallback<
  { version: string; adminToken: string } & WaitlistRouteOptions & AuthRouteOptions
> = (app, options, done) => {
  void app.register(healthRoutes, { version: options.version });
  void app.register(authRoutes, { registerRateLimit: options.registerRateLimit });
  void app.register(videoRoutes);
  void app.register(jobRoutes);
  void app.register(segmentSetRoutes);
  void app.register(waitlistRoutes, { waitlistRateLimit: options.waitlistRateLimit });
  void app.register(adminRoutes, { prefix: '/admin', adminToken: options.adminToken });
  done();
};
