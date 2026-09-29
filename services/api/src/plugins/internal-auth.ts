import { createHash, timingSafeEqual } from 'node:crypto';

import type { FastifyRequest } from 'fastify';

import { AppError } from '../errors.js';

/**
 * Builds the `onRequest` hook that guards a route scope with a shared bearer token: the worker token for
 * `/internal/*`, the admin token for `/v1/admin/*` (`tokenName` only shapes the error message).
 *
 * The comparison runs over SHA-256 digests: `timingSafeEqual` needs equal lengths, and hashing first means neither
 * the content nor the length of the configured token can be probed through response timing. The hook is only
 * attached to the internal route scope, so the public endpoints never look at this token (and a user JWT is not
 * accepted here either).
 */
export function requireInternalToken(expectedToken: string, tokenName = 'worker') {
  const expected = createHash('sha256').update(expectedToken).digest();

  return (request: FastifyRequest, _reply: unknown, done: (error?: Error) => void): void => {
    const header = request.headers.authorization;
    const presented = header?.startsWith('Bearer ') === true ? header.slice('Bearer '.length) : '';
    const actual = createHash('sha256').update(presented).digest();

    if (presented === '' || !timingSafeEqual(expected, actual)) {
      done(new AppError('unauthorized', `Missing or invalid ${tokenName} token`));
      return;
    }
    done();
  };
}
