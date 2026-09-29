import type { Selectable } from 'kysely';
import type { Static } from 'typebox';

import type { JobsTable, VideosTable } from './db/types.js';
import type { Court } from './schemas.js';

export function toJob(row: Selectable<JobsTable>) {
  return {
    id: row.id,
    videoId: row.video_id,
    status: row.status,
    progress: row.progress,
    modelVersion: row.model_version,
    error: row.error,
    attempts: row.attempts,
    createdAt: row.created_at.toISOString(),
    startedAt: row.started_at?.toISOString() ?? null,
    finishedAt: row.finished_at?.toISOString() ?? null,
  };
}

/** Builds the API's `Video` from its row and the newest job of that video, if any. */
export function toVideo(row: Selectable<VideosTable>, latestJob: Selectable<JobsTable> | undefined) {
  return {
    id: row.id,
    filename: row.filename,
    durationMs: row.duration_ms,
    width: row.width,
    height: row.height,
    fps: row.fps,
    proxySizeBytes: row.proxy_size_bytes,
    status: row.status,
    // The column is only written by the court endpoint, which validates the shape.
    court: row.court as Static<typeof Court> | null,
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at.toISOString(),
    latestJob: latestJob === undefined ? null : toJob(latestJob),
  };
}
