import react from '@vitejs/plugin-react';
import { configDefaults, defineConfig } from 'vitest/config';

import { landingPerf } from './landingPerf.ts';

export default defineConfig({
  plugins: [react(), landingPerf()],
  test: {
    // The Playwright specs in e2e/ run in a real browser through `pnpm run test:e2e`, not in Vitest.
    exclude: [...configDefaults.exclude, 'e2e/**'],
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
