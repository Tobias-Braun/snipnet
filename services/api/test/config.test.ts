import { describe, expect, it } from 'vitest';

import { loadConfig } from '../src/config.js';

const required = {
  PGUSER: 'u',
  PGPASSWORD: 'p',
  PGDATABASE: 'd',
  S3_ENDPOINT: 'http://minio:9000',
  S3_BUCKET: 'proxies',
  S3_ACCESS_KEY: 'access',
  S3_SECRET_KEY: 'secret-key',
  JWT_SECRET: 'jwt-secret-0123456789',
  INTERNAL_TOKEN: 'internal-token-0123456789',
  ADMIN_TOKEN: 'admin-token-0123456789',
};

describe('loadConfig', () => {
  it('falls back to defaults and the package version', () => {
    const config = loadConfig(required);

    expect(config).toMatchObject({
      host: '0.0.0.0',
      port: 3000,
      logLevel: 'info',
      database: { host: 'localhost', port: 5432, user: 'u', password: 'p', database: 'd', schema: undefined },
      secrets: {
        jwt: required.JWT_SECRET,
        internalToken: required.INTERNAL_TOKEN,
        adminToken: required.ADMIN_TOKEN,
      },
    });
    expect(config.version).toMatch(/^\d+\.\d+\.\d+/);
  });

  it('reads the S3 settings and signs with the internal endpoint unless a public one is set', () => {
    expect(loadConfig(required).s3).toEqual({
      endpoint: 'http://minio:9000',
      publicEndpoint: 'http://minio:9000',
      region: 'us-east-1',
      bucket: 'proxies',
      accessKey: 'access',
      secretKey: 'secret-key',
    });
    expect(
      loadConfig({ ...required, S3_PUBLIC_ENDPOINT: 'http://localhost:9000', S3_REGION: 'eu-1' }).s3,
    ).toMatchObject({
      endpoint: 'http://minio:9000',
      publicEndpoint: 'http://localhost:9000',
      region: 'eu-1',
    });
  });

  it('rejects an S3 endpoint that is not a URL', () => {
    expect(() => loadConfig({ ...required, S3_ENDPOINT: 'minio' })).toThrow(/S3_ENDPOINT/);
  });

  it('treats empty variables as unset', () => {
    const config = loadConfig({ ...required, API_PORT: '', API_HOST: ' ', LOG_LEVEL: '', APP_VERSION: '' });

    expect(config).toMatchObject({ host: '0.0.0.0', port: 3000, logLevel: 'info' });
    expect(config.version).toMatch(/^\d+\.\d+\.\d+/);
  });

  it('reads explicit values', () => {
    const config = loadConfig({
      ...required,
      API_PORT: '8080',
      API_HOST: '127.0.0.1',
      LOG_LEVEL: 'debug',
      APP_VERSION: '9.9.9',
      PGHOST: 'db',
      PGPORT: '6543',
      PGSCHEMA: 'tests_1',
    });

    expect(config).toMatchObject({
      host: '127.0.0.1',
      port: 8080,
      logLevel: 'debug',
      version: '9.9.9',
      database: { host: 'db', port: 6543, schema: 'tests_1' },
    });
  });

  it('fails fast and lists every missing variable', () => {
    expect(() => loadConfig({})).toThrow(
      /PGUSER: is required[\s\S]*PGPASSWORD: is required[\s\S]*PGDATABASE: is required[\s\S]*JWT_SECRET: is required/,
    );
  });

  it('rejects secrets that are too short', () => {
    expect(() => loadConfig({ ...required, ADMIN_TOKEN: 'short' })).toThrow(
      /ADMIN_TOKEN: must be at least 16/,
    );
  });

  it.each(['abc', '70000', '-1', '1e3', '0x1f', '80.5'])('rejects API_PORT=%s', (value) => {
    expect(() => loadConfig({ ...required, API_PORT: value })).toThrow(/API_PORT/);
  });

  it('defaults to no CORS origins, no proxy trust and 10 waitlist requests per minute', () => {
    expect(loadConfig(required)).toMatchObject({
      webOrigins: [],
      trustProxy: false,
      waitlistRateLimit: { max: 10, windowMs: 60_000 },
      registerRateLimit: { max: 5, windowMs: 60_000 },
    });
  });

  it('parses the web origins, proxy trust, waitlist and registration limits', () => {
    const config = loadConfig({
      ...required,
      WEB_ORIGIN: 'https://snipnet.app/, http://localhost:8080',
      TRUST_PROXY: '10.0.0.0/8, loopback',
      WAITLIST_RATE_LIMIT_MAX: '3',
      WAITLIST_RATE_LIMIT_WINDOW_SECONDS: '30',
      REGISTER_RATE_LIMIT_MAX: '2',
      REGISTER_RATE_LIMIT_WINDOW_SECONDS: '600',
    });

    expect(config).toMatchObject({
      webOrigins: ['https://snipnet.app', 'http://localhost:8080'],
      trustProxy: '10.0.0.0/8, loopback',
      waitlistRateLimit: { max: 3, windowMs: 30_000 },
      registerRateLimit: { max: 2, windowMs: 600_000 },
    });
  });

  it.each(['0', 'abc', '-2'])('rejects REGISTER_RATE_LIMIT_MAX=%s', (value) => {
    expect(() => loadConfig({ ...required, REGISTER_RATE_LIMIT_MAX: value })).toThrow(
      /REGISTER_RATE_LIMIT_MAX/,
    );
  });

  it.each(['true', '1'])('refuses TRUST_PROXY=%s, which would not pin down the trusted proxy', (value) => {
    expect(() => loadConfig({ ...required, TRUST_PROXY: value })).toThrow(/TRUST_PROXY/);
  });

  it.each(['0', 'abc', '-2'])('rejects WAITLIST_RATE_LIMIT_MAX=%s', (value) => {
    expect(() => loadConfig({ ...required, WAITLIST_RATE_LIMIT_MAX: value })).toThrow(
      /WAITLIST_RATE_LIMIT_MAX/,
    );
  });

  it('rejects an unknown log level and an unsafe schema name', () => {
    expect(() => loadConfig({ ...required, LOG_LEVEL: 'loud' })).toThrow(/LOG_LEVEL/);
    expect(() => loadConfig({ ...required, PGSCHEMA: 'a; drop table x' })).toThrow(/PGSCHEMA/);
  });
});
