import { randomUUID } from 'node:crypto';
import type { IncomingMessage } from 'node:http';

import fp from 'fastify-plugin';

/** Only short tokens of harmless characters are trusted, so a caller cannot inject arbitrary text into our logs. */
const SAFE_REQUEST_ID = /^[A-Za-z0-9._-]{1,64}$/;

/**
 * Fastify's `genReqId` option: keeps a well-formed `X-Request-Id` sent by a proxy or client so a request can be
 * followed across services, and generates a UUID otherwise.
 */
export function generateRequestId(request: IncomingMessage): string {
  const header = request.headers['x-request-id'];
  return typeof header === 'string' && SAFE_REQUEST_ID.test(header) ? header : randomUUID();
}

/** Echoes the request id in the `X-Request-Id` response header (the same id appears as `reqId` in every log line). */
export const requestIdPlugin = fp(
  (app, _options, done) => {
    app.addHook('onSend', (request, reply, _payload, next) => {
      void reply.header('x-request-id', request.id);
      next();
    });
    done();
  },
  { name: 'request-id' },
);
