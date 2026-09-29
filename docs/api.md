# Snipnet API contract (v1)

Source of truth for `services/api`, `services/inference` and `apps/client`. Changes to this file go in the same
PR as the implementation that needs them.

## Conventions

- Base path `/v1` (public) and `/internal` (worker only). JSON, `camelCase` fields.
- Timestamps: ISO-8601 strings (UTC). Media times: integer **milliseconds** (`startMs`, `endMs`, `durationMs`).
- Normalized image coordinates: floats in `[0, 1]`, origin top-left, relative to the video frame.
- IDs: UUID v4 strings.
- Auth: `Authorization: Bearer <jwt>` on `/v1/*` except `auth/*`, `health`, `waitlist`.
  Worker: `Authorization: Bearer <INTERNAL_TOKEN>` on `/internal/*`. Admin: `Authorization: Bearer <ADMIN_TOKEN>` on `/v1/admin/*`.
- Errors: HTTP status + `{ "error": { "code": "string_snake_case", "message": "human readable" } }`.
  Common codes: `validation_error` (400), `unauthorized` (401), `forbidden` (403), `not_found` (404),
  `conflict` (409), `rate_limited` (429), `internal` (500).

## Types

```ts
User        { id, email, trainingConsent: boolean, createdAt }
Roi         { x, y, width, height }            // normalized
Point       { x, y }                           // normalized
Court       { roi: Roi, netPoint: Point }
VideoStatus = "created" | "uploaded" | "analyzing" | "analyzed" | "failed"
CourtSuggestion { court: Court, confidence: number /*0..1*/ }
Video       { id, filename, durationMs, width, height, fps, proxySizeBytes,
              status: VideoStatus, court: Court | null, courtSuggestion: CourtSuggestion | null,
              createdAt, updatedAt, latestJob: Job | null }
JobStatus   = "queued" | "running" | "succeeded" | "failed"
Job         { id, videoId, status: JobStatus, progress: number /*0..1*/, modelVersion: string | null,
              error: string | null, attempts: number, createdAt, startedAt, finishedAt }
Segment     { startMs, endMs, label: "rally", confidence: number | null }
ScoreCurve  { hz: number, values: number[] }   // rally probability per sample, 0..1
EditOp      { op: "trim" | "split" | "merge" | "delete" | "add" | "toggle" | "move",
              atMs: number /*client epoch ms of the action*/, before: Segment[], after: Segment[] }
SegmentSet  { id, videoId, kind: "prediction" | "user", parentSetId: string | null, jobId: string | null,
              modelVersion: string | null, segments: Segment[], scores: ScoreCurve | null,
              editLog: EditOp[] | null, isFinal: boolean, createdAt }
```

Segments in a set are sorted by `startMs`, non-overlapping, `0 <= startMs < endMs <= durationMs`.

## Public endpoints

| Method & path | Body | Response |
|---|---|---|
| `GET /v1/health` | – | `200 { status: "ok", version }` |
| `POST /v1/auth/register` | `{ email, password }` (password ≥ 8 chars) | `201 { token, user }`, `409 conflict`, `429 rate_limited` (rate limited per IP, 5 per minute) |
| `POST /v1/auth/login` | `{ email, password }` | `200 { token, user }`, `401` |
| `GET /v1/me` | – | `200 User` |
| `PATCH /v1/me` | `{ trainingConsent }` | `200 User` |
| `POST /v1/videos` | `{ filename, durationMs, width, height, fps, proxySizeBytes }` | `201 { video, upload: { url, method: "PUT", headers: {…}, expiresAt } }` |
| `GET /v1/videos` | – | `200 { items: Video[] }` (own videos, newest first) |
| `GET /v1/videos/:id` | – | `200 Video` |
| `DELETE /v1/videos/:id` | – | `204` (deletes proxy object and rows); `409` while a job is `queued`/`running` |
| `POST /v1/videos/:id/upload-complete` | – | `200 Video` (status `uploaded`); `409` if object missing or size mismatch |
| `PUT /v1/videos/:id/court` | `Court` | `200 Video` |
| `POST /v1/videos/:id/analyze` | `{}` | `202 Job`; `409` if not uploaded (`created`), court missing, or a job is queued/running. Videos in `analyzed` or `failed` status can be analyzed again, see "Re-analysis" |
| `GET /v1/jobs/:id` | – | `200 Job` |
| `GET /v1/videos/:id/segment-sets` | – | `200 { items: SegmentSet[] }` (oldest first) |
| `GET /v1/segment-sets/:id` | – | `200 SegmentSet` |
| `POST /v1/videos/:id/segment-sets` | `{ parentSetId, segments, editLog, isFinal }` | `201 SegmentSet` (kind `user`); `400 validation_error` if `parentSetId` is malformed, unknown, or belongs to another video |
| `POST /v1/waitlist` | `{ email, source? }` | `202 {}` (idempotent, rate limited per IP) |
| `GET /v1/admin/training-export?since=<iso>` | – | `200` NDJSON, one line per video with a final user set of a consenting user: `{ video, proxyUrl, prediction: SegmentSet, final: SegmentSet }` |

### Re-analysis

`analyze` is allowed in every status except `created`, as long as a court is set and no job is `queued`/`running`. A
video in `analyzed` (re-analyze) or `failed` (retry) therefore gets a new job, and a successful job creates a new
`prediction` SegmentSet. Earlier sets stay as history, so a video can have several prediction sets (and user sets
descending from any of them); clients pick the set to show from `GET /v1/videos/:id/segment-sets` (oldest first)
instead of assuming one prediction per video.

### Training export

`GET /v1/admin/training-export` streams `application/x-ndjson`. `since` (ISO-8601) keeps final sets created at or
after that instant, so an incremental export passes the newest `final.createdAt` it has seen (lines may repeat).
A video with several final user sets appears once, with its newest one; `prediction` is the prediction that set
descends from via `parentSetId`. `proxyUrl` is valid for 24 hours and signed for the public storage endpoint.
A missing or wrong `ADMIN_TOKEN` yields `401`, a malformed `since` yields `400`.

## Internal (worker) endpoints

| Method & path | Body | Response |
|---|---|---|
| `POST /internal/jobs/claim` | `{ workerId }` | `200 { job, video, proxyUrl }` or `204` when queue empty. Claims oldest `queued` job (or a `running` job whose lease expired), sets `running`, increments `attempts`, lease 10 min. |
| `POST /internal/jobs/:id/progress` | `{ workerId, progress }` | `204`, extends lease |
| `POST /internal/jobs/:id/result` | `{ workerId, modelVersion, segments, scores }` | `204`; creates the `prediction` SegmentSet, job → `succeeded`, video → `analyzed` |
| `POST /internal/jobs/:id/fail` | `{ workerId, error, retryable }` | `204`; retryable and `attempts < 3` → `queued`, else `failed` (video → `failed`) |
| `POST /internal/court-detection/claim` | `{ workerId }` | `200 { videoId, proxyUrl }` or `204` when no task is queued. Claims the oldest `queued` detection task (or a `running` one whose 10 min lease expired), increments its attempts. |
| `POST /internal/videos/:id/court-suggestion` | `CourtSuggestion` or JSON `null` (no net found) | `204`; sets `Video.courtSuggestion` and finishes the task. `400` for values outside `[0, 1]` or a ROI leaving the frame, `404` unknown video or no task, `409` task not `running` |
| `POST /internal/videos/:id/court-suggestion/fail` | `{ workerId, error, retryable }` | `204`; retryable and `attempts < 3` → `queued`, else `failed`. `courtSuggestion` stays `null`. `409` when the task is not `running` or is leased to another worker |

`progress`, `result` and `fail` carry the `workerId` that was sent to `claim`. If the job is not `running` or its
`worker_id` differs (the lease expired and another worker re-claimed the job), the API answers `409` and changes
nothing. The worker treats the `409` as "lease lost": it drops the job without posting `fail`, and a `409` on
`progress` also aborts the model run early.

For court detection only the failure report carries the `workerId`: a result from a worker whose lease expired is
still a valid detection of the same proxy, so it is accepted while the task is `running`, whereas a stale failure
report must not requeue the attempt of the worker that took the task over. A `409` on the result is treated as
"lease lost" as well: the worker drops the task without posting `fail`.

## Court suggestion

The court has to be known before `analyze`, so the automatic net detection cannot run inside the analyze job.
Instead the worker detects the net once the proxy is uploaded and the result is stored on the video:

- `Video.courtSuggestion` is `null` until the detection has run and whenever no net was found. It never replaces
  `Video.court`, which stays the user's confirmed choice and the only thing `analyze` reads.
- `confidence` is in `[0, 1]`. A client applies the suggestion automatically only at `confidence >= 0.7`
  (`HIGH_CONFIDENCE` in `snipnet_ml.court_detect`), and only when the user has not saved a court yet. Lower
  values may be shown as a hint. The user always confirms or corrects the pre-filled court before saving.
- The first successful `upload-complete` enqueues one detection task for the video (a repeated call does not queue
  another). The worker claims it through `POST /internal/court-detection/claim`, downloads the proxy, calls
  `snipnet_ml.court_detect.detect_court(proxy)` and posts the outcome to `POST /internal/videos/:id/court-suggestion`:
  the `CourtSuggestion` body, or a JSON `null` body when no net was found. The endpoint validates that every
  ROI and `netPoint` coordinate and `confidence` are in `[0, 1]` and that the ROI lies inside the frame.
- Failures go to `POST /internal/videos/:id/court-suggestion/fail`. A video the detector cannot decode
  (`InvalidInputError`) is reported non-retryable, everything else retryable up to 3 attempts. Leases work as for
  jobs (10 minutes, then another worker may claim the task). The detection never changes `Video.status` and is not
  a `Job`, so a failed detection only leaves `courtSuggestion` at `null`; the user can still mark the court by hand.
- A worker claims pending detection tasks before analysis jobs, since the user waits for the pre-filled court.

Clients treat a missing `courtSuggestion` like `null`. The detector can be tried locally with
`python -m snipnet_ml.court_detect proxy.mp4`, which prints the `CourtSuggestion` JSON.

## Proxy upload

`POST /v1/videos` returns a presigned `PUT` URL (valid 1 hour) for the object `proxies/<userId>/<videoId>.mp4`.
The client sends the file as the request body with exactly the returned `headers` (`Content-Type: video/mp4`) and
a `Content-Length` equal to `proxySizeBytes`; the storage rejects anything else with `403`. Afterwards
`POST /v1/videos/:id/upload-complete` verifies the object and its size (`409` otherwise) and is idempotent.
A malformed video id is treated like an unknown one (`404`).

The first successful `upload-complete` records the object's ETag. The upload URL stays valid for its hour even
after that, so when a worker claims a job (`POST /internal/jobs/claim`) the API compares the stored object with the
recorded ETag; if the proxy was overwritten or removed in the meantime, the job and the video are set to `failed`
(job `error`: the proxy was changed or removed after the upload was confirmed) and the claim moves on to the next
job. The client re-uploads by creating a new video. An overwrite after the claim is not detected. If the object
store cannot be reached during that check, the claim answers `500` and leaves the job as it was, without spending
an attempt.

`DELETE /v1/videos/:id` is refused with `409` while the video has a `queued` or `running` job, so a worker never
loses its video mid-analysis; there is no cancel, the client retries once the job has succeeded or failed. The
check runs under the video's row lock, so it cannot race with `analyze`. Should a job vanish anyway, the
`/internal/jobs/:id/*` endpoints answer `404` and the worker drops it.

## Proxy format

MP4 (H.264, yuv420p), longest side ≤ 854 px (480p), 15 fps constant, mono AAC 16 kHz 64 kbps, `+faststart`.
Reference ffmpeg command:

```
ffmpeg -i IN -vf "scale='if(gt(iw,ih),min(854,iw),-2)':'if(gt(iw,ih),-2,min(854,ih))',fps=15" \
  -c:v libx264 -preset veryfast -crf 28 -pix_fmt yuv420p -c:a aac -ac 1 -ar 16000 -b:a 64k -movflags +faststart OUT.mp4
```
