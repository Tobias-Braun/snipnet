-- Migration 003 introduced `court_detection_tasks`, but only `upload-complete` enqueues a task, and only on its first
-- successful call. Videos that had already left status 'created' when 003 ran therefore never got one and would keep a
-- null `court_suggestion` for good. Queue a detection for each of them that still has no confirmed court; videos with
-- a court need no proposal, and ON CONFLICT keeps any task that already exists untouched.
INSERT INTO court_detection_tasks (video_id)
SELECT id FROM videos WHERE status <> 'created' AND court IS NULL
ON CONFLICT DO NOTHING;
