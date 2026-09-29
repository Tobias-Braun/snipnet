import { hash, verify } from '@node-rs/argon2';
import type { FastifyPluginCallbackTypebox } from '@fastify/type-provider-typebox';
import Type from 'typebox';

import { AppError } from '../errors.js';

/** Postgres error code for a violated unique constraint. */
const UNIQUE_VIOLATION = '23505';

/** Login attempts allowed per client address and minute before the endpoint answers `rate_limited`. */
export const LOGIN_RATE_LIMIT = { max: 10, timeWindow: '1 minute' } as const;

const User = Type.Object(
  {
    id: Type.String({ format: 'uuid' }),
    email: Type.String(),
    trainingConsent: Type.Boolean(),
    createdAt: Type.String({ format: 'date-time' }),
  },
  { $id: 'User' },
);

const Credentials = Type.Object(
  {
    // The format is checked after normalization, so " Alice@Example.com " is accepted.
    email: Type.String({ maxLength: 320 }),
    // Bounded so that hashing cannot be turned into a CPU exhaustion attack with megabyte passwords.
    password: Type.String({ maxLength: 256 }),
  },
  { additionalProperties: false },
);

const AuthResponse = Type.Object({ token: Type.String(), user: Type.Ref('User') });

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

/**
 * Hash that logins for unknown emails are verified against, so a wrong email costs as much time as a wrong
 * password and the response time does not reveal which emails are registered.
 */
let decoyHash: Promise<string> | undefined;

interface UserRow {
  id: string;
  email: string;
  training_consent: boolean;
  created_at: Date;
}

function toUser(row: UserRow) {
  return {
    id: row.id,
    email: row.email,
    trainingConsent: row.training_consent,
    createdAt: row.created_at.toISOString(),
  };
}

function isUniqueViolation(error: unknown): boolean {
  return (
    typeof error === 'object' && error !== null && (error as { code?: unknown }).code === UNIQUE_VIOLATION
  );
}

/** Registration, login and the signed-in user's own profile (`/v1/auth/*` and `/v1/me`). */
export const authRoutes: FastifyPluginCallbackTypebox = (app, _options, done) => {
  app.addSchema(User);

  const issueToken = (userId: string) => app.jwt.sign({ sub: userId });

  app.post(
    '/auth/register',
    {
      schema: {
        tags: ['auth'],
        body: Credentials,
        response: {
          201: AuthResponse,
          400: Type.Ref('ErrorResponse'),
          409: Type.Ref('ErrorResponse'),
        },
      },
    },
    async (request, reply) => {
      // Fastify's default ajv setup strips unknown body fields instead of rejecting them, so extra fields are ignored.
      const email = normalizeEmail(request.body.email);
      if (!EMAIL_PATTERN.test(email)) {
        throw new AppError('validation_error', 'email must be a valid email address');
      }
      if (request.body.password.length < 8) {
        throw new AppError('validation_error', 'password must be at least 8 characters');
      }

      // @node-rs/argon2 hashes with argon2id by default.
      const passwordHash = await hash(request.body.password);
      try {
        const row = await app.db
          .insertInto('users')
          .values({ email, password_hash: passwordHash })
          .returning(['id', 'email', 'training_consent', 'created_at'])
          .executeTakeFirstOrThrow();
        void reply.code(201);
        return { token: issueToken(row.id), user: toUser(row) };
      } catch (error) {
        if (isUniqueViolation(error)) throw new AppError('conflict', 'Email is already registered');
        throw error;
      }
    },
  );

  app.post(
    '/auth/login',
    {
      config: { rateLimit: LOGIN_RATE_LIMIT },
      schema: {
        tags: ['auth'],
        body: Credentials,
        response: {
          200: AuthResponse,
          401: Type.Ref('ErrorResponse'),
          429: Type.Ref('ErrorResponse'),
        },
      },
    },
    async (request) => {
      const email = normalizeEmail(request.body.email);
      const row = await app.db
        .selectFrom('users')
        .select(['id', 'email', 'password_hash', 'training_consent', 'created_at'])
        // Emails are unique case-insensitively (see the migration), and stored normalized.
        .where((eb) => eb(eb.fn('lower', ['email']), '=', email))
        .executeTakeFirst();

      decoyHash ??= hash('decoy-password-for-unknown-emails');
      const valid = await verify(row?.password_hash ?? (await decoyHash), request.body.password);
      if (row === undefined || !valid) {
        throw new AppError('unauthorized', 'Invalid email or password');
      }
      return { token: issueToken(row.id), user: toUser(row) };
    },
  );

  app.get(
    '/me',
    {
      preHandler: app.authenticate,
      schema: {
        tags: ['auth'],
        security: [{ bearerAuth: [] }],
        response: { 200: Type.Ref('User'), 401: Type.Ref('ErrorResponse') },
      },
    },
    async (request) => {
      const row = await app.db
        .selectFrom('users')
        .select(['id', 'email', 'training_consent', 'created_at'])
        .where('id', '=', request.user.sub)
        .executeTakeFirst();
      // A valid token of a deleted account is as good as no token.
      if (row === undefined) throw new AppError('unauthorized', 'Account no longer exists');
      return toUser(row);
    },
  );

  app.patch(
    '/me',
    {
      preHandler: app.authenticate,
      schema: {
        tags: ['auth'],
        security: [{ bearerAuth: [] }],
        body: Type.Object({ trainingConsent: Type.Boolean() }, { additionalProperties: false }),
        response: { 200: Type.Ref('User'), 400: Type.Ref('ErrorResponse'), 401: Type.Ref('ErrorResponse') },
      },
    },
    async (request) => {
      const row = await app.db
        .updateTable('users')
        .set({ training_consent: request.body.trainingConsent })
        .where('id', '=', request.user.sub)
        .returning(['id', 'email', 'training_consent', 'created_at'])
        .executeTakeFirst();
      if (row === undefined) throw new AppError('unauthorized', 'Account no longer exists');
      return toUser(row);
    },
  );

  done();
};
