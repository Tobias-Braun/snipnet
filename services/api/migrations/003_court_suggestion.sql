-- Automatic court proposal for the video: `{ court: {roi, netPoint}, confidence }`, or null until the detection ran and
-- whenever no net was found. It never replaces `videos.court`, which stays the user's confirmed choice.
ALTER TABLE videos ADD COLUMN court_suggestion jsonb;

-- One detection task per video, enqueued by `upload-complete` and worked off by the inference worker. Kept apart from
-- `jobs` because a detection does not touch the video's analysis status and must not show up as `latestJob`.
CREATE TABLE court_detection_tasks (
  video_id uuid PRIMARY KEY REFERENCES videos (id) ON DELETE CASCADE,
  status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
  attempts integer NOT NULL DEFAULT 0,
  worker_id text,
  lease_expires_at timestamptz,
  error text,
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Backs the worker's claim query (oldest queued task, or a running task with an expired lease).
CREATE INDEX court_detection_tasks_claimable_idx ON court_detection_tasks (created_at) WHERE status IN ('queued', 'running');
