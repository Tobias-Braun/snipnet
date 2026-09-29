import { randomBytes } from 'node:crypto';

import {
  CreateBucketCommand,
  DeleteBucketCommand,
  DeleteObjectCommand,
  ListObjectsV2Command,
  S3Client,
} from '@aws-sdk/client-s3';

import type { S3Config } from '../../src/config.js';

export interface TestBucket {
  /** The given S3 settings, pointed at the fresh bucket. */
  config: S3Config;
  client: S3Client;
  /** Empties and removes the bucket and closes the client. */
  drop: () => Promise<void>;
}

/**
 * Creates a randomly named bucket on the MinIO from the environment (`S3_ENDPOINT` and credentials, see
 * `test/helpers/db.ts`), so every test file works on its own objects like it does with Postgres schemas.
 */
export async function createTestBucket(base: S3Config): Promise<TestBucket> {
  const bucket = `test-${randomBytes(6).toString('hex')}`;
  const client = new S3Client({
    endpoint: base.endpoint,
    region: base.region,
    credentials: { accessKeyId: base.accessKey, secretAccessKey: base.secretKey },
    forcePathStyle: true,
  });
  await client.send(new CreateBucketCommand({ Bucket: bucket }));

  return {
    config: { ...base, bucket },
    client,
    drop: async () => {
      try {
        const listed = await client.send(new ListObjectsV2Command({ Bucket: bucket }));
        for (const object of listed.Contents ?? []) {
          await client.send(new DeleteObjectCommand({ Bucket: bucket, Key: object.Key }));
        }
        await client.send(new DeleteBucketCommand({ Bucket: bucket }));
      } finally {
        client.destroy();
      }
    },
  };
}
