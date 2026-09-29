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
  const port = Number(env.API_PORT ?? '3000');
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error(`API_PORT must be an integer between 0 and 65535, got "${env.API_PORT ?? ''}"`);
  }

  return {
    host: env.API_HOST ?? '0.0.0.0',
    port,
    logLevel: env.LOG_LEVEL ?? 'info',
    version: env.APP_VERSION ?? packageVersion(),
  };
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
