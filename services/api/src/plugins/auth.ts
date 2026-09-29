import type { FastifyReply, FastifyRequest } from 'fastify';
import fastifyJwt from '@fastify/jwt';
import fp from 'fastify-plugin';

import { AppError } from '../errors.js';

/** Lifetime of an access token; the client re-authenticates with the stored credentials after it expires. */
export const TOKEN_EXPIRY = '30d';

declare module '@fastify/jwt' {
  interface FastifyJWT {
    /** The user id travels in the standard `sub` claim. */
    payload: { sub: string };
    user: { sub: string };
  }
}

declare module 'fastify' {
  interface FastifyInstance {
    /**
     * `onRequest` hook for every route that needs a signed-in user. It runs before body parsing and schema
     * validation, so an unauthenticated request always gets 401 (never a 400 leaking the schema) and its body is
     * never parsed. It verifies the bearer token and exposes the user id as `request.user.sub`; a missing,
     * malformed, tampered or expired token becomes `unauthorized`.
     */
    authenticate: (request: FastifyRequest, reply: FastifyReply) => Promise<void>;
  }
}

/** Registers `@fastify/jwt` (HS256) and the `authenticate` decorator that the feature routes reuse. */
export const authPlugin = fp<{ secret: string }>(
  async (app, options) => {
    await app.register(fastifyJwt, {
      secret: options.secret,
      sign: { algorithm: 'HS256', expiresIn: TOKEN_EXPIRY },
      verify: { algorithms: ['HS256'] },
    });

    app.decorate('authenticate', async (request: FastifyRequest) => {
      try {
        await request.jwtVerify();
      } catch {
        throw new AppError('unauthorized', 'Missing or invalid access token');
      }
    });
  },
  { name: 'auth' },
);
