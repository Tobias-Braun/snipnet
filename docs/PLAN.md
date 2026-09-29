# Snipnet — plan and architecture

Snipnet turns raw roundnet footage into rally clips. A tripod camera records a match (net roughly in the
center of the frame, often other courts playing in the background). The backend AI splits the video into
**rally** and **dead time** segments; the desktop app shows them on a video-editing timeline where the user
corrects them and exports the result. Every correction is stored next to the original prediction so later
model versions can be trained on it.

## Guiding decisions

| Topic | Decision | Reason |
|---|---|---|
| Client | Kotlin Multiplatform + Compose Multiplatform, **desktop first** (macOS/Windows/Linux). Mobile (Android/iOS review app) later, on the same `shared` module. | Desktop is where a real timeline editor makes sense. |
| AI inference | **Backend only**, never on the device. | Every prediction and every correction has to reach the backend to build training data. |
| Upload | The client uploads a **low-res proxy** (≤480p, 15 fps, mono AAC 16 kHz) made with bundled ffmpeg. The original stays local and is used for playback and export. | Raw tripod footage is several GB per hour; the proxy is ~50–100 MB. |
| Background courts | The user marks the **net point and a court region (ROI)** on a frame once per video. Automatic net detection comes later and pre-fills it. | One click removes most of the background-court problem for the v0 model. |
| Hosting | Local `docker compose` for now (Postgres, MinIO, API, worker). Everything stays portable. | No cloud decision yet. |
| Licenses | Only permissive ML dependencies (PyTorch/torchvision BSD, librosa ISC, …). **No Ultralytics (AGPL).** | Keeps a commercial option open. |

## Repository layout

```
apps/client/          Gradle KMP project: shared/ (domain, editing core, API client, local store), desktopApp/ (Compose Desktop UI, video engine)
apps/web/             Landing page: React + Vite + TypeScript + GSAP
services/api/         Fastify (TypeScript) API, Postgres (migrations owned here), S3/MinIO
services/inference/   Python worker: polls the API for jobs, runs the model, posts results
ml/                   Python package `snipnet_ml`: features, models, evaluation, training, fixtures
infra/                docker-compose.yml and service configs
docs/                 This plan, API contract (docs/api.md), ADRs (docs/adr/)
```

Tooling: pnpm workspace (`apps/web`, `services/api`), uv workspace (`ml`, `services/inference`), Gradle wrapper in
`apps/client`, GitHub Actions per area with path filters.

## System overview

```
Desktop app ──(JWT, JSON)──> Fastify API ──> Postgres
     │                          │  └──────> MinIO (proxies)
     │ presigned PUT            │
     └──────────> MinIO         └──(internal token)── Inference worker (Python, polls /internal/jobs/claim)
```

1. The user imports a video. The client probes it, transcodes the proxy and asks the user to mark the net/ROI.
2. `POST /v1/videos` → presigned PUT URL → upload proxy → `POST /v1/videos/:id/upload-complete`.
3. `PUT /v1/videos/:id/court`, then `POST /v1/videos/:id/analyze` creates an inference job.
4. The worker claims the job (`FOR UPDATE SKIP LOCKED` in the API), downloads the proxy, reports progress, and posts
   the result: a **prediction segment set** (immutable) plus a rally-probability curve.
5. The client shows the prediction on the timeline. User edits create **user segment sets** (with parent pointer
   and edit log). Marking one as final makes the (prediction, final) pair usable as training data if the user
   consented.
6. Export happens locally with ffmpeg on the original file.

The full HTTP contract lives in [api.md](api.md). It is the source of truth that client, API and worker
implement independently.

## AI / ML

### v0 — heuristic, no training data needed (`model_version = heuristic-v0.x`)

Per 0.5 s window, computed on the proxy restricted to the court ROI:

- **Motion energy** in the ROI (frame differencing / dense optical flow on a downscaled crop).
- **People in the ROI**: person detections (torchvision detector, COCO "person") whose foot point lies inside the
  expanded ROI → count, spread, mean distance to the net point, movement speed.
- **Audio hits**: onset strength in the hit frequency band and number of sharp transients per window — roundnet
  hits are short, loud impulses; distant background courts are quieter.

A weighted score becomes a rally probability. A 2-state HMM (Viterbi) or hysteresis smoothing then produces
segments, with minimum rally length, minimum gap and padding (default: 0.5 s before and after).

### v1 — learned from corrections

Dataset builder from the training export (only consenting users, split **by video**), pretrained frozen video and audio
embeddings on ROI crops, small temporal model (TCN or tiny transformer) trained per window, same smoothing as
v0. Promoted only if it beats v0 on the held-out set.

### Evaluation

Labels are rally lists `{start_ms, end_ms}` — the same shape as a final user segment set, so the desktop app is
also the labeling tool. Metrics: segment precision/recall/F1 at IoU ≥ 0.5, frame-level accuracy, mean absolute
boundary error (s). A synthetic fixture generator (ffmpeg: moving shapes + click sounds during "rallies")
gives deterministic end-to-end tests in CI. Real footage (a few clips provided later) is used for tuning and
must never be committed.

## Desktop editor

- Projects list, import (file picker / drag and drop), proxy + upload progress, analysis status.
- Court selection: frame preview, click the net, drag the ROI.
- Timeline: thumbnail strip, audio waveform, AI probability heat strip, segment track with rally blocks, playhead.
  Zoom and scroll, drag to trim, split (S), delete, mark in/out (I/O), merge, accept/reject a segment, undo/redo,
  J/K/L playback, next/previous rally, "play rallies only" preview.
- Save: user segment set with edit log; offline drafts in a local store; "mark final".
- Export: one concatenated rally video, one file per rally, or FCPXML/EDL for pro editors.
- Video engine: FFmpeg through JavaCV (`org.bytedeco`) for probing, frame-accurate seeking, playback, thumbnails,
  waveform, proxy transcode and export. Validated by a spike issue first.

## Milestones

| | Milestone | Content |
|---|---|---|
| M0 | Foundation | Monorepo scaffolding, CI, docker compose |
| M1 | Backend | API: auth, videos/uploads, jobs, segment sets, training export, waitlist |
| M2 | Inference v0 | Worker, fixtures/eval, features, heuristic model, end-to-end test |
| M3 | Desktop editor | App shell, editing core, video engine, import/upload, court selection, timeline, save, export, packaging |
| M4 | Landing page | React/GSAP landing page, waitlist, downloads |
| M5 | Learning loop | v1 training pipeline, tuning on real footage, auto net detection |
| M6 | Mobile | Android and iOS review apps |

Work is tracked as GitHub issues (label `area:*`, milestone `M*`). Each issue lists its dependencies.
