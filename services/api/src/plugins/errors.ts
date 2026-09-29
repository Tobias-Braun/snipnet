import type { FastifyError } from 'fastify';
import fp from 'fastify-plugin';

import { AppError, ERROR_STATUS } from '../errors.js';

/** Codes for 4xx statuses raised by Fastify itself (bad JSON, oversized body, ...) that are not `AppError`s. */
const CODE_BY_STATUS: Record<number, string> = {
  ...Object.fromEntries(Object.entries(ERROR_STATUS).map(([code, status]) => [status, code])),
  413: 'payload_too_large',
  415: 'unsupported_media_type',
};

/**
 * Makes every failure use the contract's `{ error: { code, message } }` shape: thrown `AppError`s, schema
 * validation failures (`validation_error`), Fastify's own client errors, unknown routes and unexpected
 * exceptions. Internals of 5xx errors are logged but never sent to the client.
 */
export const errorsPlugin = fp(
  (app, _options, done) => {
    app.setErrorHandler((error: FastifyError | AppError, request, reply) => {
      if (error instanceof AppError) {
        return reply.code(error.statusCode).send({ error: { code: error.code, message: error.message } });
      }

      if ('validation' in error) {
        return reply.code(400).send({ error: { code: 'validation_error', message: error.message } });
      }

      const status = error.statusCode ?? 500;
      if (status >= 400 && status < 500) {
        const code = CODE_BY_STATUS[status] ?? 'bad_request';
        return reply.code(status).send({ error: { code, message: error.message } });
      }

      request.log.error({ err: error }, 'unhandled error');
      return reply.code(500).send({ error: { code: 'internal', message: 'Internal server error' } });
    });

    app.setNotFoundHandler((request, reply) => {
      return reply
        .code(404)
        .send({ error: { code: 'not_found', message: `Route ${request.method} ${request.url} not found` } });
    });

    done();
  },
  { name: 'errors' },
);
