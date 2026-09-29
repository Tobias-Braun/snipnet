-- ETag of the proxy object as it was when the client reported the upload as complete. The worker claim compares it
-- with the stored object, so a proxy overwritten through the still-valid upload URL is not analysed. Null for videos
-- that have not completed an upload yet.
ALTER TABLE videos ADD COLUMN proxy_etag text;
