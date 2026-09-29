import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import Type from 'typebox';

import { buildApp } from '../src/app.js';
import { AppError } from '../src/errors.js';
import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';

describe('API application', () => {
  let schema: TestSchema;
  let app: Awaited<ReturnType<typeof buildApp>> | undefined;

  beforeAll(async () => {
    schema = await createTestSchema();
    const db = createDb(schema.config.database);
    try {
      await runMigrations(db);
    } finally {
      await db.destroy();
    }
  });

  afterEach(async () => {
    await app?.close();
    app = undefined;
  });

  afterAll(async () => {
    await schema.drop();
  });

  async function newApp() {
    app = await buildApp({ config: { ...schema.config, version: '1.2.3' } });
    app.post(
      '/v1/test/echo',
      {
        schema: {
          body: Type.Object({ email: Type.String({ format: 'email' }) }, { additionalProperties: false }),
        },
      },
      (request) => request.body,
    );
    app.get('/v1/test/conflict', () => {
      throw new AppError('conflict', 'Already there');
    });
    app.get('/v1/test/boom', () => {
      throw new Error('secret internal detail');
    });
    app.get('/v1/test/db', async (request) => {
      const row = await request.server.db.selectFrom('users').select('id').limit(1).execute();
      return { count: row.length };
    });
    return app;
  }

  it('reports ok and the configured version', async () => {
    const response = await (await newApp()).inject({ method: 'GET', url: '/v1/health' });

    expect(response.statusCode).toBe(200);
    expect(response.headers['content-type']).toMatch(/^application\/json/);
    expect(response.json()).toEqual({ status: 'ok', version: '1.2.3' });
  });

  it('is only served under the /v1 prefix', async () => {
    const response = await (await newApp()).inject({ method: 'GET', url: '/health' });

    expect(response.statusCode).toBe(404);
    expect(response.json()).toMatchObject({ error: { code: 'not_found' } });
  });

  it('queries the migrated database through app.db', async () => {
    const response = await (await newApp()).inject({ method: 'GET', url: '/v1/test/db' });

    expect(response.json()).toEqual({ count: 0 });
  });

  describe('error handling', () => {
    it('maps schema violations to validation_error', async () => {
      const response = await (
        await newApp()
      ).inject({
        method: 'POST',
        url: '/v1/test/echo',
        payload: { email: 'not-an-email' },
      });

      expect(response.statusCode).toBe(400);
      expect(response.json()).toEqual({
        error: { code: 'validation_error', message: expect.stringContaining('email') as string },
      });
    });

    it('maps malformed JSON to a 400 error body', async () => {
      const response = await (
        await newApp()
      ).inject({
        method: 'POST',
        url: '/v1/test/echo',
        headers: { 'content-type': 'application/json' },
        payload: '{"email":',
      });

      expect(response.statusCode).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    });

    it('uses the status and code of a thrown AppError', async () => {
      const response = await (await newApp()).inject({ method: 'GET', url: '/v1/test/conflict' });

      expect(response.statusCode).toBe(409);
      expect(response.json()).toEqual({ error: { code: 'conflict', message: 'Already there' } });
    });

    it('hides the details of unexpected errors', async () => {
      const response = await (await newApp()).inject({ method: 'GET', url: '/v1/test/boom' });

      expect(response.statusCode).toBe(500);
      expect(response.json()).toEqual({ error: { code: 'internal', message: 'Internal server error' } });
    });
  });

  describe('request ids', () => {
    it('generates one and echoes it in the response header', async () => {
      const response = await (await newApp()).inject({ method: 'GET', url: '/v1/health' });

      expect(response.headers['x-request-id']).toMatch(/^[0-9a-f-]{36}$/);
    });

    it('keeps a well-formed id from the caller', async () => {
      const response = await (
        await newApp()
      ).inject({
        method: 'GET',
        url: '/v1/health',
        headers: { 'x-request-id': 'trace-abc.123' },
      });

      expect(response.headers['x-request-id']).toBe('trace-abc.123');
    });

    it('replaces an id with unsafe characters', async () => {
      const response = await (
        await newApp()
      ).inject({
        method: 'GET',
        url: '/v1/health',
        headers: { 'x-request-id': 'bad id\tinjected' },
      });

      expect(response.headers['x-request-id']).toMatch(/^[0-9a-f-]{36}$/);
    });
  });

  describe('OpenAPI document', () => {
    it('is served at /v1/openapi.json and describes the routes', async () => {
      const response = await (await newApp()).inject({ method: 'GET', url: '/v1/openapi.json' });

      expect(response.statusCode).toBe(200);
      const document = response.json<{
        openapi: string;
        info: { version: string };
        paths: Record<string, unknown>;
      }>();
      expect(document.openapi).toBe('3.1.0');
      expect(document.info.version).toBe('1.2.3');
      expect(document.paths).toHaveProperty(['/v1/health', 'get']);
      expect(document.paths).not.toHaveProperty('/v1/openapi.json');
    });
  });
});
