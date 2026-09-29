import type { ColumnType, Generated } from 'kysely';

/** Read as a `Date`; inserts may omit it (the column defaults to `now()`) or pass a date or ISO string. */
type Timestamp = Generated<Date>;

/** A `jsonb` column: read as parsed JSON, written as a serialized string (pg cannot infer jsonb from arrays). */
type Json<T> = ColumnType<T, string, string>;

export interface UsersTable {
  id: Generated<string>;
  email: string;
  password_hash: string;
  training_consent: Generated<boolean>;
  created_at: Timestamp;
}

export interface VideosTable {
  id: Generated<string>;
  user_id: string;
  filename: string;
  duration_ms: number;
  width: number;
  height: number;
  fps: number;
  proxy_size_bytes: number;
  status: Generated<'created' | 'uploaded' | 'analyzing' | 'analyzed' | 'failed'>;
  /** `{ roi: {x,y,width,height}, netPoint: {x,y} }` once the user has marked the court. */
  court: ColumnType<unknown, string | null | undefined, string | null>;
  /** Key of the proxy object in the S3 bucket. */
  object_key: string;
  /** ETag of the proxy object when `upload-complete` accepted it; the worker claim verifies it is unchanged. */
  proxy_etag: string | null;
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface JobsTable {
  id: Generated<string>;
  video_id: string;
  status: Generated<'queued' | 'running' | 'succeeded' | 'failed'>;
  progress: Generated<number>;
  model_version: string | null;
  error: string | null;
  attempts: Generated<number>;
  worker_id: string | null;
  /** A running job whose lease has expired can be claimed by another worker. */
  lease_expires_at: Date | null;
  created_at: Timestamp;
  started_at: Date | null;
  finished_at: Date | null;
}

export interface SegmentSetsTable {
  id: Generated<string>;
  video_id: string;
  kind: 'prediction' | 'user';
  parent_set_id: string | null;
  job_id: string | null;
  model_version: string | null;
  segments: Json<unknown[]>;
  scores: ColumnType<unknown, string | null | undefined, string | null>;
  edit_log: ColumnType<unknown, string | null | undefined, string | null>;
  is_final: Generated<boolean>;
  created_at: Timestamp;
}

export interface WaitlistTable {
  id: Generated<string>;
  email: string;
  source: string | null;
  created_at: Timestamp;
}

export interface Database {
  users: UsersTable;
  videos: VideosTable;
  jobs: JobsTable;
  segment_sets: SegmentSetsTable;
  waitlist: WaitlistTable;
}
