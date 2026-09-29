import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';

import { buildApp } from '../src/app.js';
import { createDb } from '../src/db/client.js';
import { runMigrations } from '../src/db/migrate.js';
import { createTestSchema, type TestSchema } from './helpers/db.js';

const WEB_ORIGIN = 'https://snipnet.example';

describe('POST /v1/waitlist', () => {
  let schema: TestSchema;
  let app: Awaited<ReturnType<typeof buildApp>>;
  /** Whether the current test built an app that still has to be cleaned up. */
  let appOpen = false;

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
    if (!appOpen) return;
    await app.db.deleteFrom('waitlist').execute();
    await app.close();
    appOpen = false;
  });

  afterAll(async () => {
    await schema.drop();
  });

  async function newApp(rateLimitMax = 100, trustProxy: false | string = false) {
    app = await buildApp({
      config: {
        ...schema.config,
        trustProxy,
        webOrigins: [WEB_ORIGIN],
        waitlistRateLimit: { max: rateLimitMax, windowMs: 60_000 },
      },
    });
    appOpen = true;
  }

  function signUp(payload: unknown, headers: Record<string, string> = {}) {
    return app.inject({ method: 'POST', url: '/v1/waitlist', payload: payload as object, headers });
  }

  it('stores the address lowercased with its source and answers 202', async () => {
    await newApp();

    const response = await signUp({ email: 'Fan@Example.com', source: 'hero' });

    expect(response.statusCode).toBe(202);
    expect(response.json()).toEqual({});
    const rows = await app.db.selectFrom('waitlist').select(['email', 'source']).execute();
    expect(rows).toEqual([{ email: 'fan@example.com', source: 'hero' }]);
  });

  it('accepts a sign-up without a source', async () => {
    await newApp();

    expect((await signUp({ email: 'a@example.com' })).statusCode).toBe(202);
    const rows = await app.db.selectFrom('waitlist').select('source').execute();
    expect(rows).toEqual([{ source: null }]);
  });

  it('is idempotent regardless of case and keeps the first source', async () => {
    await newApp();

    const first = await signUp({ email: 'a@example.com', source: 'hero' });
    const again = await signUp({ email: 'A@EXAMPLE.com', source: 'footer' });

    expect(first.statusCode).toBe(202);
    expect(again.statusCode).toBe(202);
    expect(again.json()).toEqual({});
    const rows = await app.db.selectFrom('waitlist').select(['email', 'source']).execute();
    expect(rows).toEqual([{ email: 'a@example.com', source: 'hero' }]);
  });

  it.each([
    ['a missing email', {}],
    ['a malformed email', { email: 'not-an-email' }],
    ['a non-string email', { email: 42 }],
    ['an overlong email', { email: `${'a'.repeat(250)}@example.com` }],
    ['an empty source', { email: 'a@example.com', source: '' }],
    ['an overlong source', { email: 'a@example.com', source: 'x'.repeat(65) }],
  ])('rejects %s with validation_error', async (_name, payload) => {
    await newApp();

    const response = await signUp(payload);

    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    expect(await app.db.selectFrom('waitlist').select('id').execute()).toHaveLength(0);
  });

  it('answers 429 rate_limited once an IP exceeds the limit, without affecting other IPs', async () => {
    await newApp(2);

    const statuses: number[] = [];
    for (let i = 0; i < 3; i++) {
      statuses.push((await signUp({ email: `n${String(i)}@example.com` })).statusCode);
    }
    const limited = await signUp({ email: 'later@example.com' });

    expect(statuses).toEqual([202, 202, 429]);
    expect(limited.json()).toEqual({
      error: { code: 'rate_limited', message: expect.any(String) as string },
    });
    expect(limited.headers['retry-after']).toBeDefined();
    expect(await app.db.selectFrom('waitlist').select('id').execute()).toHaveLength(2);

    const otherIp = await app.inject({
      method: 'POST',
      url: '/v1/waitlist',
      payload: { email: 'other@example.com' },
      remoteAddress: '203.0.113.7',
    });
    expect(otherIp.statusCode).toBe(202);
  });

  it('ignores X-Forwarded-For while TRUST_PROXY is off, so a client cannot dodge the limit by forging it', async () => {
    await newApp(2);

    const statuses: number[] = [];
    for (let i = 0; i < 3; i++) {
      const response = await signUp(
        { email: `f${String(i)}@example.com` },
        { 'x-forwarded-for': `198.51.100.${String(i)}` },
      );
      statuses.push(response.statusCode);
    }

    expect(statuses).toEqual([202, 202, 429]);
  });

  it('behind a trusted proxy, limits by the address the proxy appended, not a client-forged one', async () => {
    // Injected requests come from 127.0.0.1, which plays the reverse proxy here.
    await newApp(2, '127.0.0.1');

    // A proxy like nginx appends the peer address, so the client controls every entry left of the last one.
    const statuses: number[] = [];
    for (let i = 0; i < 3; i++) {
      const response = await signUp(
        { email: `p${String(i)}@example.com` },
        { 'x-forwarded-for': `10.9.9.${String(i)}, 198.51.100.4` },
      );
      statuses.push(response.statusCode);
    }
    const otherClient = await signUp(
      { email: 'q@example.com' },
      { 'x-forwarded-for': '10.9.9.0, 198.51.100.5' },
    );

    expect(statuses).toEqual([202, 202, 429]);
    expect(otherClient.statusCode).toBe(202);
  });

  describe('CORS', () => {
    it('allows the landing page origin, including the preflight', async () => {
      await newApp();

      const preflight = await app.inject({
        method: 'OPTIONS',
        url: '/v1/waitlist',
        headers: {
          origin: WEB_ORIGIN,
          'access-control-request-method': 'POST',
          'access-control-request-headers': 'content-type',
        },
      });
      const actual = await signUp({ email: 'a@example.com' }, { origin: WEB_ORIGIN });

      expect(preflight.statusCode).toBe(204);
      expect(preflight.headers['access-control-allow-origin']).toBe(WEB_ORIGIN);
      expect(preflight.headers['access-control-allow-methods']).toContain('POST');
      expect(actual.headers['access-control-allow-origin']).toBe(WEB_ORIGIN);
    });

    it('does not allow other origins', async () => {
      await newApp();

      const response = await signUp({ email: 'a@example.com' }, { origin: 'https://evil.example' });

      expect(response.headers['access-control-allow-origin']).toBeUndefined();
    });
  });
});
