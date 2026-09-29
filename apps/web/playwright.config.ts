import { createServer } from 'node:net';

import { defineConfig } from '@playwright/test';

/**
 * Asks the OS for a free TCP port. Several jobs share one machine, so the preview server never binds a
 * fixed port.
 */
async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const probe = createServer();
    probe.once('error', reject);
    probe.listen(0, '127.0.0.1', () => {
      const address = probe.address();
      probe.close(() => {
        if (address !== null && typeof address === 'object') {
          resolve(address.port);
        } else {
          reject(new Error('Could not determine a free port'));
        }
      });
    });
  });
}

// Worker processes re-evaluate this file and inherit the parent's environment, so the port chosen by
// the main process is stored there to keep every process pointing at the same preview server.
process.env.E2E_PORT ??= String(await freePort());
const baseURL = `http://127.0.0.1:${process.env.E2E_PORT}`;

export default defineConfig({
  testDir: './e2e',
  // Baselines carry no platform suffix: they are rendered in the pinned Playwright container (see
  // `pnpm run test:e2e:update`) and CI runs in that same image, so one set of PNGs is authoritative.
  snapshotPathTemplate: '{testDir}/__screenshots__/{arg}-{projectName}{ext}',
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    colorScheme: 'light',
    reducedMotion: 'reduce',
  },
  projects: [
    { name: 'mobile-360', use: { browserName: 'chromium', viewport: { width: 360, height: 800 } } },
    { name: 'desktop-1280', use: { browserName: 'chromium', viewport: { width: 1280, height: 800 } } },
  ],
  webServer: {
    // Node runs Vite directly: going through `pnpm exec` leaves an intermediate process that does not
    // forward the shutdown signal, so the preview server would outlive the test run and hang it.
    command: `node ./node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port ${process.env.E2E_PORT} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
  },
});
