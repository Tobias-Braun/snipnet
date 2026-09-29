import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';

import { buildApp } from '../src/app.js';
import { LOGIN_RATE_LIMIT } from '../src/routes/auth.js';
import type { TestSchema } from './helpers/db.js';
import { createMigratedTestSchema } from './helpers/seed.js';

interface AuthBody {
  token: string;
  user: { id: string; email: string; trainingConsent: boolean; createdAt: string };
}

describe('auth routes', () => {
  let schema: TestSchema;
  let app: Awaited<ReturnType<typeof buildApp>>;
  let counter = 0;

  beforeAll(async () => {
    schema = await createMigratedTestSchema();
  });

  afterEach(async () => {
    await app.close();
  });

  afterAll(async () => {
    await schema.drop();
  });

  /** A fresh app per test also gives each test its own in-memory rate limit counters. */
  async function newApp() {
    app = await buildApp({ config: schema.config });
    return app;
  }

  function uniqueEmail(): string {
    counter += 1;
    return `user${String(counter)}@example.com`;
  }

  async function register(email: string, password = 'correct horse') {
    return app.inject({ method: 'POST', url: '/v1/auth/register', payload: { email, password } });
  }

  describe('POST /v1/auth/register', () => {
    it('creates the user and returns a token and the user', async () => {
      await newApp();
      const email = uniqueEmail();
      const response = await register(email);

      expect(response.statusCode).toBe(201);
      const body = response.json<AuthBody>();
      expect(body.token).toEqual(expect.any(String));
      expect(body.user).toEqual({
        id: expect.stringMatching(/^[0-9a-f-]{36}$/) as string,
        email,
        trainingConsent: false,
        createdAt: expect.stringMatching(/^\d{4}-\d\d-\d\dT.*Z$/) as string,
      });
      expect(JSON.stringify(body)).not.toContain('password');
    });

    it('stores the password as an argon2id hash', async () => {
      await newApp();
      const email = uniqueEmail();
      await register(email, 'plain-secret-password');

      const row = await app.db
        .selectFrom('users')
        .select('password_hash')
        .where('email', '=', email)
        .executeTakeFirstOrThrow();
      expect(row.password_hash).toMatch(/^\$argon2id\$/);
      expect(row.password_hash).not.toContain('plain-secret-password');
    });

    it('signs an HS256 token with a 30 day expiry', async () => {
      await newApp();
      const { token, user } = (await register(uniqueEmail())).json<AuthBody>();

      const [encodedHeader = '', encodedPayload = ''] = token.split('.');
      const decode = (part: string): unknown => JSON.parse(Buffer.from(part, 'base64url').toString());
      const header = decode(encodedHeader) as { alg: string };
      const payload = decode(encodedPayload) as { sub: string; iat: number; exp: number };
      expect(header.alg).toBe('HS256');
      expect(payload.sub).toBe(user.id);
      expect(payload.exp - payload.iat).toBe(30 * 24 * 60 * 60);
    });

    it('trims and lowercases the email', async () => {
      await newApp();
      const email = uniqueEmail();
      const response = await register(`  ${email.toUpperCase()} `);

      expect(response.statusCode).toBe(201);
      expect(response.json<AuthBody>().user.email).toBe(email);
    });

    it('rejects a second registration of the same email regardless of case with 409', async () => {
      await newApp();
      const email = uniqueEmail();
      await register(email);
      const response = await register(` ${email.toUpperCase()}`);

      expect(response.statusCode).toBe(409);
      expect(response.json()).toMatchObject({ error: { code: 'conflict' } });
    });

    it.each([
      ['a malformed email', { email: 'not-an-email', password: 'long enough pw' }],
      ['an email that is blank after trimming', { email: '   ', password: 'long enough pw' }],
      ['a password shorter than 8 characters', { email: 'short@example.com', password: '1234567' }],
      ['a missing password', { email: 'nopw@example.com' }],
      ['a missing email', { password: 'long enough pw' }],
      ['a non-string email', { email: 42, password: 'long enough pw' }],
      ['an oversized password', { email: 'big@example.com', password: 'x'.repeat(257) }],
    ])('rejects %s with validation_error', async (_name, payload) => {
      await newApp();
      const response = await app.inject({ method: 'POST', url: '/v1/auth/register', payload });

      expect(response.statusCode).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    });

    it('counts password length in characters, not bytes', async () => {
      await newApp();
      const response = await register(uniqueEmail(), 'ééééééé');

      expect(response.statusCode).toBe(400);
    });
  });

  describe('POST /v1/auth/login', () => {
    it('returns a working token and the user for the right credentials', async () => {
      await newApp();
      const email = uniqueEmail();
      const registered = (await register(email, 'my login password')).json<AuthBody>();

      const response = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        payload: { email: `${email.toUpperCase()} `, password: 'my login password' },
      });

      expect(response.statusCode).toBe(200);
      const body = response.json<AuthBody>();
      expect(body.user).toEqual(registered.user);
      const me = await app.inject({
        method: 'GET',
        url: '/v1/me',
        headers: { authorization: `Bearer ${body.token}` },
      });
      expect(me.statusCode).toBe(200);
    });

    it('answers 401 for a wrong password and for an unknown email alike', async () => {
      await newApp();
      const email = uniqueEmail();
      await register(email, 'my login password');

      const wrongPassword = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        payload: { email, password: 'not the password' },
      });
      const unknownEmail = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        payload: { email: uniqueEmail(), password: 'my login password' },
      });

      expect(wrongPassword.statusCode).toBe(401);
      expect(unknownEmail.statusCode).toBe(401);
      expect(unknownEmail.json()).toEqual(wrongPassword.json());
      expect(wrongPassword.json()).toMatchObject({ error: { code: 'unauthorized' } });
    });

    it('rejects a malformed body with validation_error', async () => {
      await newApp();
      const response = await app.inject({
        method: 'POST',
        url: '/v1/auth/login',
        payload: { email: 'a@b.co' },
      });

      expect(response.statusCode).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    });

    it('is rate limited with the rate_limited error code', async () => {
      await newApp();
      const attempt = () =>
        app.inject({
          method: 'POST',
          url: '/v1/auth/login',
          payload: { email: 'nobody@example.com', password: 'whatever password' },
        });

      for (let i = 0; i < LOGIN_RATE_LIMIT.max; i += 1) {
        expect((await attempt()).statusCode).toBe(401);
      }
      const limited = await attempt();

      expect(limited.statusCode).toBe(429);
      expect(limited.json()).toMatchObject({ error: { code: 'rate_limited' } });
    });

    it('keys the limit on X-Forwarded-For only when the proxy is trusted', async () => {
      const attemptFrom = (client: string) =>
        app.inject({
          method: 'POST',
          url: '/v1/auth/login',
          headers: { 'x-forwarded-for': client },
          payload: { email: 'nobody@example.com', password: 'whatever password' },
        });
      const exhaust = async (client: (i: number) => string) => {
        const statuses: number[] = [];
        for (let i = 0; i <= LOGIN_RATE_LIMIT.max; i += 1) {
          statuses.push((await attemptFrom(client(i))).statusCode);
        }
        return statuses;
      };

      // Untrusted: every request counts against the peer (127.0.0.1) whatever the header claims.
      app = await buildApp({ config: { ...schema.config, trustProxy: false } });
      const untrusted = await exhaust((i) => `198.51.100.${String(i)}`);
      expect(untrusted).toEqual([...Array<number>(LOGIN_RATE_LIMIT.max).fill(401), 429]);

      // Trusted: the peer is the proxy, so each distinct client address gets its own budget.
      await app.close();
      app = await buildApp({ config: { ...schema.config, trustProxy: '127.0.0.1' } });
      const trusted = await exhaust((i) => `198.51.100.${String(i)}`);
      expect(trusted).toEqual(Array<number>(LOGIN_RATE_LIMIT.max + 1).fill(401));
    });
  });

  describe('POST /v1/auth/register rate limit', () => {
    it('is rate limited per client address with the rate_limited error code', async () => {
      const max = 5;
      app = await buildApp({
        config: { ...schema.config, registerRateLimit: { max, windowMs: 60_000 } },
      });
      const attempt = () =>
        app.inject({ method: 'POST', url: '/v1/auth/register', payload: { email: 'bad', password: 'x' } });

      for (let i = 0; i < max; i += 1) {
        expect((await attempt()).statusCode).toBe(400);
      }
      const limited = await attempt();

      expect(limited.statusCode).toBe(429);
      expect(limited.json()).toMatchObject({ error: { code: 'rate_limited' } });
    });

    it('caps well-formed registrations per client address without blocking other clients', async () => {
      const max = 2;
      // Trusting the loopback peer lets X-Forwarded-For stand in for distinct client addresses.
      app = await buildApp({
        config: { ...schema.config, trustProxy: '127.0.0.1', registerRateLimit: { max, windowMs: 60_000 } },
      });
      const registerFrom = (client: string) =>
        app.inject({
          method: 'POST',
          url: '/v1/auth/register',
          headers: { 'x-forwarded-for': client },
          payload: { email: uniqueEmail(), password: 'correct horse' },
        });

      const statuses: number[] = [];
      for (let i = 0; i <= max; i += 1) {
        statuses.push((await registerFrom('198.51.100.1')).statusCode);
      }
      const otherClient = await registerFrom('198.51.100.2');

      expect(statuses).toEqual([...Array<number>(max).fill(201), 429]);
      expect(otherClient.statusCode).toBe(201);
    });
  });

  describe('GET /v1/me', () => {
    it('returns the signed-in user', async () => {
      await newApp();
      const { token, user } = (await register(uniqueEmail())).json<AuthBody>();

      const response = await app.inject({
        method: 'GET',
        url: '/v1/me',
        headers: { authorization: `Bearer ${token}` },
      });

      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual(user);
    });

    it('requires a token', async () => {
      await newApp();
      const response = await app.inject({ method: 'GET', url: '/v1/me' });

      expect(response.statusCode).toBe(401);
      expect(response.json()).toMatchObject({ error: { code: 'unauthorized' } });
    });

    it('rejects garbage, foreign-signed and expired tokens', async () => {
      await newApp();
      const { user } = (await register(uniqueEmail())).json<AuthBody>();
      const foreign = await buildApp({
        config: { ...schema.config, secrets: { ...schema.config.secrets, jwt: 'another-secret-0123456789' } },
      });
      const foreignToken = foreign.jwt.sign({ sub: user.id });
      await foreign.close();
      const expired = app.jwt.sign({ sub: user.id }, { expiresIn: '-1s' });

      for (const token of ['garbage', foreignToken, expired]) {
        const response = await app.inject({
          method: 'GET',
          url: '/v1/me',
          headers: { authorization: `Bearer ${token}` },
        });
        expect(response.statusCode).toBe(401);
        expect(response.json()).toMatchObject({ error: { code: 'unauthorized' } });
      }
    });

    it('rejects a valid token of a deleted account', async () => {
      await newApp();
      const { token, user } = (await register(uniqueEmail())).json<AuthBody>();
      await app.db.deleteFrom('users').where('id', '=', user.id).execute();

      const response = await app.inject({
        method: 'GET',
        url: '/v1/me',
        headers: { authorization: `Bearer ${token}` },
      });

      expect(response.statusCode).toBe(401);
    });
  });

  describe('PATCH /v1/me', () => {
    it('grants and withdraws training consent', async () => {
      await newApp();
      const { token } = (await register(uniqueEmail())).json<AuthBody>();
      const patch = (payload: object) =>
        app.inject({
          method: 'PATCH',
          url: '/v1/me',
          headers: { authorization: `Bearer ${token}` },
          payload,
        });

      const granted = await patch({ trainingConsent: true });
      expect(granted.statusCode).toBe(200);
      expect(granted.json<AuthBody['user']>().trainingConsent).toBe(true);

      const me = await app.inject({
        method: 'GET',
        url: '/v1/me',
        headers: { authorization: `Bearer ${token}` },
      });
      expect(me.json<AuthBody['user']>().trainingConsent).toBe(true);

      const withdrawn = await patch({ trainingConsent: false });
      expect(withdrawn.json<AuthBody['user']>().trainingConsent).toBe(false);
    });

    it('requires a token', async () => {
      await newApp();
      const response = await app.inject({
        method: 'PATCH',
        url: '/v1/me',
        payload: { trainingConsent: true },
      });

      expect(response.statusCode).toBe(401);
    });

    it('answers 401 before validating the body of an unauthenticated request', async () => {
      await newApp();
      const response = await app.inject({
        method: 'PATCH',
        url: '/v1/me',
        payload: { trainingConsent: 'yes' },
      });

      expect(response.statusCode).toBe(401);
      expect(response.json()).toMatchObject({ error: { code: 'unauthorized' } });
    });

    it('ignores fields other than trainingConsent', async () => {
      await newApp();
      const email = uniqueEmail();
      const { token } = (await register(email)).json<AuthBody>();
      const response = await app.inject({
        method: 'PATCH',
        url: '/v1/me',
        headers: { authorization: `Bearer ${token}` },
        payload: { trainingConsent: true, email: 'other@example.com' },
      });

      expect(response.statusCode).toBe(200);
      expect(response.json<AuthBody['user']>().email).toBe(email);
    });

    it.each([
      ['a non-boolean value', { trainingConsent: 'yes' }],
      ['a missing field', {}],
    ])('rejects %s with validation_error', async (_name, payload) => {
      await newApp();
      const { token } = (await register(uniqueEmail())).json<AuthBody>();
      const response = await app.inject({
        method: 'PATCH',
        url: '/v1/me',
        headers: { authorization: `Bearer ${token}` },
        payload,
      });

      expect(response.statusCode).toBe(400);
      expect(response.json()).toMatchObject({ error: { code: 'validation_error' } });
    });
  });
});
