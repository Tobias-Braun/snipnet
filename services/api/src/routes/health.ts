import type { FastifyPluginCallback } from 'fastify';
import Type from 'typebox';

export interface HealthRouteOptions {
  version: string;
}

export const healthRoutes: FastifyPluginCallback<HealthRouteOptions> = (app, options, done) => {
  app.get(
    '/health',
    {
      schema: {
        tags: ['health'],
        response: {
          200: Type.Object({ status: Type.Literal('ok'), version: Type.String() }),
        },
      },
    },
    () => ({ status: 'ok', version: options.version }),
  );

  done();
};
