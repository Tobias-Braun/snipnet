import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Every test file migrates and drops its own Postgres schema. Kysely's migrator looks for its bookkeeping table
    // with a catalog query over all schemas, which fails with "schema ... does not exist" when another file drops
    // its schema at the same moment. Running files one after another removes that race; the suite takes seconds.
    fileParallelism: false,
  },
});
