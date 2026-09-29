import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';

import { buildApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';

describe('GET /v1/health', () => {
  let app: FastifyInstance | undefined;

  afterEach(async () => {
    await app?.close();
    app = undefined;
  });

  it('reports ok and the configured version', async () => {
    app = await buildApp({ version: '1.2.3' });

    const response = await app.inject({ method: 'GET', url: '/v1/health' });

    expect(response.statusCode).toBe(200);
    expect(response.headers['content-type']).toMatch(/^application\/json/);
    expect(response.json()).toEqual({ status: 'ok', version: '1.2.3' });
  });

  it('is only served under the /v1 prefix', async () => {
    app = await buildApp({ version: '1.2.3' });

    const response = await app.inject({ method: 'GET', url: '/health' });

    expect(response.statusCode).toBe(404);
  });
});

describe('loadConfig', () => {
  it('falls back to defaults and the package version', () => {
    const config = loadConfig({});

    expect(config).toMatchObject({ host: '0.0.0.0', port: 3000, logLevel: 'info' });
    expect(config.version).toMatch(/^\d+\.\d+\.\d+/);
  });

  it('treats empty variables as unset', () => {
    const config = loadConfig({ API_PORT: '', API_HOST: ' ', LOG_LEVEL: '', APP_VERSION: '' });

    expect(config).toMatchObject({ host: '0.0.0.0', port: 3000, logLevel: 'info' });
    expect(config.version).toMatch(/^\d+\.\d+\.\d+/);
  });

  it('reads explicit values', () => {
    expect(
      loadConfig({ API_PORT: '8080', API_HOST: '127.0.0.1', LOG_LEVEL: 'debug', APP_VERSION: '9.9.9' }),
    ).toEqual({ host: '127.0.0.1', port: 8080, logLevel: 'debug', version: '9.9.9' });
  });

  it.each(['abc', '70000', '-1', '1e3', '0x1f', '80.5'])('rejects API_PORT=%s', (value) => {
    expect(() => loadConfig({ API_PORT: value })).toThrow(/API_PORT/);
  });
});
