import { readFileSync } from 'node:fs';

export interface ServerConfig {
  host: string;
  port: number;
  logLevel: string;
  version: string;
}

/**
 * Reads the server configuration from environment variables (documented in `infra/.env.example`).
 * Invalid values fail fast at startup instead of surfacing later as a confusing bind error.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  const rawPort = valueOf(env, 'API_PORT') ?? '3000';
  // A strict digit check, because Number() also accepts "", "0x1f" or "1e3" and would bind an unexpected port.
  const port = /^\d+$/.test(rawPort) ? Number(rawPort) : NaN;
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error(`API_PORT must be an integer between 0 and 65535, got "${rawPort}"`);
  }

  return {
    host: valueOf(env, 'API_HOST') ?? '0.0.0.0',
    port,
    logLevel: valueOf(env, 'LOG_LEVEL') ?? 'info',
    version: valueOf(env, 'APP_VERSION') ?? packageVersion(),
  };
}

/**
 * Treats a variable that is set but empty (e.g. `API_PORT=` in an env file) like an unset one, so it falls back
 * to the default instead of producing an empty host, version or port 0.
 */
function valueOf(env: NodeJS.ProcessEnv, name: string): string | undefined {
  const value = env[name]?.trim();
  return value === undefined || value === '' ? undefined : value;
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
