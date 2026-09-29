-- Base tables for the entities in docs/api.md. Timestamps are timestamptz and IDs are server-generated UUIDs.

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL,
  password_hash text NOT NULL,
  training_consent boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Emails are compared case-insensitively, so "A@x.io" and "a@x.io" cannot both register.
CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));

CREATE TABLE videos (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  filename text NOT NULL,
  duration_ms integer NOT NULL CHECK (duration_ms > 0),
  width integer NOT NULL CHECK (width > 0),
  height integer NOT NULL CHECK (height > 0),
  fps double precision NOT NULL CHECK (fps > 0),
  proxy_size_bytes bigint NOT NULL CHECK (proxy_size_bytes > 0),
  status text NOT NULL DEFAULT 'created' CHECK (status IN ('created', 'uploaded', 'analyzing', 'analyzed', 'failed')),
  court jsonb,
  object_key text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX videos_user_created_idx ON videos (user_id, created_at DESC);

CREATE TABLE jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  video_id uuid NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
  status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
  progress double precision NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 1),
  model_version text,
  error text,
  attempts integer NOT NULL DEFAULT 0,
  worker_id text,
  lease_expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  started_at timestamptz,
  finished_at timestamptz
);
CREATE INDEX jobs_video_created_idx ON jobs (video_id, created_at DESC);
-- Backs the worker's claim query (oldest queued job, or a running job with an expired lease).
CREATE INDEX jobs_claimable_idx ON jobs (created_at) WHERE status IN ('queued', 'running');
-- At most one active job per video, which turns a racing double "analyze" into a conflict instead of two jobs.
CREATE UNIQUE INDEX jobs_one_active_per_video_key ON jobs (video_id) WHERE status IN ('queued', 'running');

CREATE TABLE segment_sets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  video_id uuid NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('prediction', 'user')),
  parent_set_id uuid REFERENCES segment_sets (id) ON DELETE SET NULL,
  job_id uuid REFERENCES jobs (id) ON DELETE SET NULL,
  model_version text,
  segments jsonb NOT NULL,
  scores jsonb,
  edit_log jsonb,
  is_final boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX segment_sets_video_created_idx ON segment_sets (video_id, created_at);
-- Backs the training export, which only looks at final sets.
CREATE INDEX segment_sets_final_idx ON segment_sets (created_at) WHERE is_final;

CREATE TABLE waitlist (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL,
  source text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX waitlist_email_lower_key ON waitlist (lower(email));
