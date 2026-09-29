import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

import { landingPerf } from './landingPerf.ts';

export default defineConfig({
  plugins: [react(), landingPerf()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
