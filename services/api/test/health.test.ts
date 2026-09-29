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

  it('rejects a port that is not a valid TCP port', () => {
    expect(() => loadConfig({ API_PORT: 'abc' })).toThrow(/API_PORT/);
    expect(() => loadConfig({ API_PORT: '70000' })).toThrow(/API_PORT/);
  });
});
