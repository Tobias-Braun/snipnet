import { readFileSync } from 'node:fs';

import { z } from 'zod';

export interface DatabaseConfig {
  host: string;
  port: number;
  user: string;
  password: string;
  database: string;
  /** When set, every connection uses this schema first (`search_path`); integration tests isolate themselves with it. */
  schema: string | undefined;
}

export interface S3Config {
  /** Endpoint the API itself uses for HEAD and DELETE requests (inside Docker: `http://minio:9000`). */
  endpoint: string;
  /** Endpoint embedded in presigned URLs; must be reachable from the desktop app, not just from the API. */
  publicEndpoint: string;
  region: string;
  bucket: string;
  accessKey: string;
  secretKey: string;
}

export interface AppConfig {
  host: string;
  port: number;
  logLevel: string;
  version: string;
  database: DatabaseConfig;
  s3: S3Config;
  /** Secrets for the auth, worker and admin endpoints that later issues implement. */
  secrets: { jwt: string; internalToken: string; adminToken: string };
  /** Origins of the landing page that may call the public endpoints cross-origin; empty disables CORS. */
  webOrigins: string[];
  /**
   * Which proxies may set the client IP via `X-Forwarded-For`: `false` (none) or a comma separated list of proxy
   * addresses, CIDRs or proxy-addr names such as `loopback` and `uniquelocal`, passed to Fastify's `trustProxy`.
   */
  trustProxy: false | string;
  /** Per-IP limit of the unauthenticated waitlist endpoint. */
  waitlistRateLimit: { max: number; windowMs: number };
  /** Per-IP limit of registration, which costs an argon2id hash and a users row per request. */
  registerRateLimit: { max: number; windowMs: number };
}

const LOG_LEVELS = ['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'] as const;

/** A secret that is missing or too short to be safe fails startup instead of running with a guessable value. */
const secret = z.string().min(16, 'must be at least 16 characters');

const port = z
  .string()
  // A strict digit check, because Number() also accepts "0x1f" or "1e3" and would bind an unexpected port.
  .regex(/^\d+$/, 'must be an integer between 0 and 65535')
  .transform(Number)
  .pipe(z.number().int().min(0).max(65535));

const positiveInt = z
  .string()
  .regex(/^\d+$/, 'must be a positive integer')
  .transform(Number)
  .pipe(z.number().int().min(1));

const envSchema = z.object({
  API_HOST: z.string().default('0.0.0.0'),
  API_PORT: port.default(3000),
  LOG_LEVEL: z.enum(LOG_LEVELS).default('info'),
  APP_VERSION: z.string().optional(),
  PGHOST: z.string().default('localhost'),
  PGPORT: port.default(5432),
  PGUSER: z.string(),
  PGPASSWORD: z.string(),
  PGDATABASE: z.string(),
  PGSCHEMA: z
    .string()
    .regex(/^[a-z_][a-z0-9_]*$/, 'must be a lowercase SQL identifier')
    .optional(),
  S3_ENDPOINT: z.url(),
  S3_PUBLIC_ENDPOINT: z.url().optional(),
  S3_REGION: z.string().default('us-east-1'),
  S3_BUCKET: z.string().min(3),
  S3_ACCESS_KEY: z.string(),
  S3_SECRET_KEY: z.string(),
  JWT_SECRET: secret,
  INTERNAL_TOKEN: secret,
  ADMIN_TOKEN: secret,
  WEB_ORIGIN: z.string().optional(),
  // `true` is refused on purpose: Fastify would then take the left-most X-Forwarded-For entry, which the client
  // writes itself when a proxy appends to the header, so anyone could evade the per-IP rate limit. Hop counts are
  // refused because Fastify ignores them (it cannot verify the immediate peer that way).
  TRUST_PROXY: z
    .string()
    .refine((value) => value !== 'true' && !/^\d+$/.test(value), {
      message: 'must be false or the proxy addresses/CIDRs; true or a hop count would not identify the proxy',
    })
    .default('false'),
  WAITLIST_RATE_LIMIT_MAX: positiveInt.default(10),
  WAITLIST_RATE_LIMIT_WINDOW_SECONDS: positiveInt.default(60),
  REGISTER_RATE_LIMIT_MAX: positiveInt.default(5),
  REGISTER_RATE_LIMIT_WINDOW_SECONDS: positiveInt.default(60),
});

/**
 * Reads and validates the configuration from environment variables (documented in `infra/.env.example`).
 * Every problem is collected into a single error so a misconfigured deployment fails at startup with the
 * complete list of what to fix instead of one variable per restart.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = envSchema.safeParse(withoutEmptyValues(env));
  if (!parsed.success) {
    const problems = parsed.error.issues
      .map(
        (issue) =>
          `  ${issue.path.join('.')}: ${issue.code === 'invalid_type' ? 'is required' : issue.message}`,
      )
      .join('\n');
    throw new Error(`Invalid environment configuration:\n${problems}`);
  }
  const values = parsed.data;

  return {
    host: values.API_HOST,
    port: values.API_PORT,
    logLevel: values.LOG_LEVEL,
    version: values.APP_VERSION ?? packageVersion(),
    database: {
      host: values.PGHOST,
      port: values.PGPORT,
      user: values.PGUSER,
      password: values.PGPASSWORD,
      database: values.PGDATABASE,
      schema: values.PGSCHEMA,
    },
    s3: {
      endpoint: values.S3_ENDPOINT,
      publicEndpoint: values.S3_PUBLIC_ENDPOINT ?? values.S3_ENDPOINT,
      region: values.S3_REGION,
      bucket: values.S3_BUCKET,
      accessKey: values.S3_ACCESS_KEY,
      secretKey: values.S3_SECRET_KEY,
    },
    secrets: { jwt: values.JWT_SECRET, internalToken: values.INTERNAL_TOKEN, adminToken: values.ADMIN_TOKEN },
    webOrigins: (values.WEB_ORIGIN ?? '')
      .split(',')
      .map((origin) => origin.trim().replace(/\/+$/, ''))
      .filter((origin) => origin !== ''),
    trustProxy: values.TRUST_PROXY === 'false' ? false : values.TRUST_PROXY,
    waitlistRateLimit: {
      max: values.WAITLIST_RATE_LIMIT_MAX,
      windowMs: values.WAITLIST_RATE_LIMIT_WINDOW_SECONDS * 1000,
    },
    registerRateLimit: {
      max: values.REGISTER_RATE_LIMIT_MAX,
      windowMs: values.REGISTER_RATE_LIMIT_WINDOW_SECONDS * 1000,
    },
  };
}

/**
 * Treats a variable that is set but blank (e.g. `API_PORT=` in an env file) like an unset one, so it falls back
 * to the default (or is reported as missing) instead of producing an empty host or port 0.
 */
function withoutEmptyValues(env: NodeJS.ProcessEnv): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [name, value] of Object.entries(env)) {
    const trimmed = value?.trim();
    if (trimmed !== undefined && trimmed !== '') result[name] = trimmed;
  }
  return result;
}

/**
 * The package.json sits one level above both `src/` (tsx in development) and `dist/` (compiled build), so the
 * same relative URL works in both modes.
 */
function packageVersion(): string {
  const raw = readFileSync(new URL('../package.json', import.meta.url), 'utf8');
  const { version } = JSON.parse(raw) as { version: string };
  return version;
}
