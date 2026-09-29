import {
  DeleteObjectCommand,
  HeadObjectCommand,
  NotFound,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import fp from 'fastify-plugin';

import type { S3Config } from '../config.js';

/** How long a presigned upload URL stays valid. */
export const UPLOAD_URL_TTL_SECONDS = 60 * 60;

/** The only content type proxies are uploaded with; it is part of the signature, so clients cannot vary it. */
export const PROXY_CONTENT_TYPE = 'video/mp4';

export interface PresignedUpload {
  url: string;
  method: 'PUT';
  /** Headers the client must send unchanged, otherwise the signature does not match. */
  headers: Record<string, string>;
  expiresAt: Date;
}

/** The object store operations the API needs, so routes do not depend on the AWS SDK directly. */
export interface Storage {
  /** Presigned PUT for `key` that only accepts a `video/mp4` body of exactly `sizeBytes` bytes. */
  presignUpload: (key: string, sizeBytes: number) => Promise<PresignedUpload>;
  /** Size of the stored object in bytes, or `null` when the key does not exist. */
  objectSize: (key: string) => Promise<number | null>;
  /** Removes the object; deleting a key that does not exist is not an error. */
  deleteObject: (key: string) => Promise<void>;
}

declare module 'fastify' {
  interface FastifyInstance {
    storage: Storage;
  }
}

/** Object key of a video's proxy; the user id in the path keeps a bucket browsable per owner. */
export function proxyKey(userId: string, videoId: string): string {
  return `proxies/${userId}/${videoId}.mp4`;
}

function createClient(config: S3Config, endpoint: string): S3Client {
  return new S3Client({
    endpoint,
    region: config.region,
    credentials: { accessKeyId: config.accessKey, secretAccessKey: config.secretKey },
    // MinIO serves buckets under the path (`http://host/bucket/key`), not as a subdomain.
    forcePathStyle: true,
    // The SDK's default CRC32 checksum header would otherwise be signed into presigned URLs, and clients that
    // upload with plain HTTP (not the SDK) would not send it.
    requestChecksumCalculation: 'WHEN_REQUIRED',
    responseChecksumValidation: 'WHEN_REQUIRED',
  });
}

/**
 * Creates the storage operations for a bucket. Presigning is a local computation, but the host is part of the
 * signature, so it needs a client that is configured with the public endpoint. Requests the API makes itself
 * go through a second client on the internal endpoint.
 */
export function createStorage(config: S3Config): Storage & { close: () => void } {
  const internal = createClient(config, config.endpoint);
  const signer = createClient(config, config.publicEndpoint);

  return {
    async presignUpload(key, sizeBytes) {
      const command = new PutObjectCommand({
        Bucket: config.bucket,
        Key: key,
        ContentType: PROXY_CONTENT_TYPE,
        ContentLength: sizeBytes,
      });
      const url = await getSignedUrl(signer, command, {
        expiresIn: UPLOAD_URL_TTL_SECONDS,
        // Content-Type is not signed by default; without this, any content type would be accepted.
        signableHeaders: new Set(['content-type']),
      });
      return {
        url,
        method: 'PUT',
        headers: { 'Content-Type': PROXY_CONTENT_TYPE },
        expiresAt: new Date(Date.now() + UPLOAD_URL_TTL_SECONDS * 1000),
      };
    },

    async objectSize(key) {
      try {
        const head = await internal.send(new HeadObjectCommand({ Bucket: config.bucket, Key: key }));
        return head.ContentLength ?? null;
      } catch (error) {
        // HEAD responses have no body, so the SDK reports a missing key as the generic NotFound error.
        if (error instanceof NotFound) return null;
        throw error;
      }
    },

    async deleteObject(key) {
      await internal.send(new DeleteObjectCommand({ Bucket: config.bucket, Key: key }));
    },

    close() {
      internal.destroy();
      signer.destroy();
    },
  };
}

/** Decorates the app with `app.storage` and releases the S3 connections when the app closes. */
export const storagePlugin = fp<{ s3: S3Config }>(
  (app, options, done) => {
    const storage = createStorage(options.s3);
    app.decorate('storage', storage);
    app.addHook('onClose', () => {
      storage.close();
    });
    done();
  },
  { name: 'storage' },
);
