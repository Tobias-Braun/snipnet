import { describe, expect, it } from 'vitest';

import { loadConfig } from '../src/config.js';

const required = {
  PGUSER: 'u',
  PGPASSWORD: 'p',
  PGDATABASE: 'd',
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

  it('rejects an unknown log level and an unsafe schema name', () => {
    expect(() => loadConfig({ ...required, LOG_LEVEL: 'loud' })).toThrow(/LOG_LEVEL/);
    expect(() => loadConfig({ ...required, PGSCHEMA: 'a; drop table x' })).toThrow(/PGSCHEMA/);
  });
});
