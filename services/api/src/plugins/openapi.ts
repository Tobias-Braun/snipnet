import swagger from '@fastify/swagger';
import fp from 'fastify-plugin';

/**
 * Generates the OpenAPI document from the route schemas and serves it at `/v1/openapi.json`. It has to be
 * registered before the routes so that swagger sees them as they are added.
 */
export const openapiPlugin = fp<{ version: string }>(
  async (app, options) => {
    await app.register(swagger, {
      openapi: {
        openapi: '3.1.0',
        info: { title: 'Snipnet API', version: options.version },
        components: {
          securitySchemes: {
            bearerAuth: { type: 'http', scheme: 'bearer' },
            internalToken: {
              type: 'http',
              scheme: 'bearer',
              description: 'Shared INTERNAL_TOKEN of the worker.',
            },
            adminToken: {
              type: 'http',
              scheme: 'bearer',
              description: 'Shared ADMIN_TOKEN of the operator.',
            },
          },
        },
      },
    });

    app.get('/v1/openapi.json', { schema: { hide: true } }, () => app.swagger());
  },
  { name: 'openapi' },
);
