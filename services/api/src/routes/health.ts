import type { FastifyPluginCallback } from 'fastify';

export interface HealthRouteOptions {
  version: string;
}

export const healthRoutes: FastifyPluginCallback<HealthRouteOptions> = (app, options, done) => {
  app.get(
    '/health',
    {
      schema: {
        response: {
          200: {
            type: 'object',
            required: ['status', 'version'],
            properties: {
              status: { type: 'string', const: 'ok' },
              version: { type: 'string' },
            },
          },
        },
      },
    },
    () => ({ status: 'ok', version: options.version }),
  );

  done();
};
