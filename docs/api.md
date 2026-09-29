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
Video       { id, filename, durationMs, width, height, fps, proxySizeBytes,
              status: VideoStatus, court: Court | null, createdAt, updatedAt,
              latestJob: Job | null }
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
| `POST /v1/auth/register` | `{ email, password }` (password ≥ 8 chars) | `201 { token, user }`, `409 conflict` |
| `POST /v1/auth/login` | `{ email, password }` | `200 { token, user }`, `401` |
| `GET /v1/me` | – | `200 User` |
| `PATCH /v1/me` | `{ trainingConsent }` | `200 User` |
| `POST /v1/videos` | `{ filename, durationMs, width, height, fps, proxySizeBytes }` | `201 { video, upload: { url, method: "PUT", headers: {…}, expiresAt } }` |
| `GET /v1/videos` | – | `200 { items: Video[] }` (own videos, newest first) |
| `GET /v1/videos/:id` | – | `200 Video` |
| `DELETE /v1/videos/:id` | – | `204` (deletes proxy object and rows) |
| `POST /v1/videos/:id/upload-complete` | – | `200 Video` (status `uploaded`); `409` if object missing or size mismatch |
| `PUT /v1/videos/:id/court` | `Court` | `200 Video` |
| `POST /v1/videos/:id/analyze` | `{}` | `202 Job`; `409` if not uploaded, court missing, or a job is queued/running |
| `GET /v1/jobs/:id` | – | `200 Job` |
| `GET /v1/videos/:id/segment-sets` | – | `200 { items: SegmentSet[] }` (oldest first) |
| `GET /v1/segment-sets/:id` | – | `200 SegmentSet` |
| `POST /v1/videos/:id/segment-sets` | `{ parentSetId, segments, editLog, isFinal }` | `201 SegmentSet` (kind `user`) |
| `POST /v1/waitlist` | `{ email, source? }` | `202 {}` (idempotent, rate limited per IP) |
| `GET /v1/admin/training-export?since=<iso>` | – | `200` NDJSON, one line per video with a final user set of a consenting user: `{ video, proxyUrl, prediction: SegmentSet, final: SegmentSet }` |

## Internal (worker) endpoints

| Method & path | Body | Response |
|---|---|---|
| `POST /internal/jobs/claim` | `{ workerId }` | `200 { job, video, proxyUrl }` or `204` when queue empty. Claims oldest `queued` job (or a `running` job whose lease expired), sets `running`, increments `attempts`, lease 10 min. |
| `POST /internal/jobs/:id/progress` | `{ progress }` | `204`, extends lease |
| `POST /internal/jobs/:id/result` | `{ modelVersion, segments, scores }` | `204`; creates the `prediction` SegmentSet, job → `succeeded`, video → `analyzed` |
| `POST /internal/jobs/:id/fail` | `{ error, retryable }` | `204`; retryable and `attempts < 3` → `queued`, else `failed` (video → `failed`) |

## Proxy format

MP4 (H.264, yuv420p), longest side ≤ 854 px (480p), 15 fps constant, mono AAC 16 kHz 64 kbps, `+faststart`.
Reference ffmpeg command:

```
ffmpeg -i IN -vf "scale='if(gt(iw,ih),min(854,iw),-2)':'if(gt(iw,ih),-2,min(854,ih))',fps=15" \
  -c:v libx264 -preset veryfast -crf 28 -pix_fmt yuv420p -c:a aac -ac 1 -ar 16000 -b:a 64k -movflags +faststart OUT.mp4
```
