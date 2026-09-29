"""End-to-end test of the whole backend pipeline against a running Docker Compose stack.

It plays the part of the desktop client over plain HTTP: register, create a video, upload a synthetic proxy to
the presigned URL, set the court, start the analysis, poll the job until the real worker container has
processed it, fetch the prediction and post a final user segment set. The prediction is then compared with the
ground truth of the generated fixture, loosely enough that heuristic tuning does not break it but tightly
enough that a broken pipeline (empty result, wrong time base, wrong court) does.

The test needs a stack, so it only runs when `E2E_API_URL` is set. `infra/e2e/run.sh` starts the stack on free
ports and sets the variable; the pytest configuration of the repository does not collect this folder by default.
"""

from __future__ import annotations

import os
import time
import uuid
from pathlib import Path

import httpx
import pytest

from snipnet_ml.eval import evaluate, format_table
from snipnet_ml.fixtures import DEFAULT_COURT, FPS, HEIGHT, WIDTH, generate_video
from snipnet_ml.labels import Rally

API_URL = os.environ.get("E2E_API_URL")
JOB_TIMEOUT_S = float(os.environ.get("E2E_JOB_TIMEOUT_S", "240"))

pytestmark = pytest.mark.skipif(API_URL is None, reason="E2E_API_URL is not set, use infra/e2e/run.sh")


def _check(response: httpx.Response, expected: int) -> httpx.Response:
    assert response.status_code == expected, f"{response.request.method} {response.request.url}: {response.text}"
    return response


def test_upload_analyze_and_correct(tmp_path: Path) -> None:
    assert API_URL is not None
    proxy = tmp_path / "fixture.mp4"
    labels = generate_video(proxy, duration_s=60, seed=7)
    size = proxy.stat().st_size

    with httpx.Client(base_url=f"{API_URL}/v1", timeout=30) as api:
        _check(api.get("/health"), 200)

        email = f"e2e-{uuid.uuid4().hex[:12]}@example.com"
        registered = _check(api.post("/auth/register", json={"email": email, "password": "e2e-password-1"}), 201)
        api.headers["Authorization"] = f"Bearer {registered.json()['token']}"

        created = _check(
            api.post(
                "/videos",
                json={
                    "filename": "fixture.mp4",
                    "durationMs": labels.duration_ms,
                    "width": WIDTH,
                    "height": HEIGHT,
                    "fps": FPS,
                    "proxySizeBytes": size,
                },
            ),
            201,
        ).json()
        video_id = created["video"]["id"]
        upload = created["upload"]

        # The presigned URL is signed for exactly these headers and length, so the API token must not be sent.
        _check(httpx.put(upload["url"], content=proxy.read_bytes(), headers=upload["headers"], timeout=60), 200)
        assert _check(api.post(f"/videos/{video_id}/upload-complete"), 200).json()["status"] == "uploaded"

        court = DEFAULT_COURT.model_dump(by_alias=True)
        assert _check(api.put(f"/videos/{video_id}/court", json=court), 200).json()["court"] == court

        job = _check(api.post(f"/videos/{video_id}/analyze", json={}), 202).json()
        deadline = time.monotonic() + JOB_TIMEOUT_S
        while job["status"] not in ("succeeded", "failed"):
            assert time.monotonic() < deadline, f"job did not finish within {JOB_TIMEOUT_S}s: {job}"
            time.sleep(2)
            job = _check(api.get(f"/jobs/{job['id']}"), 200).json()
        assert job["status"] == "succeeded", job
        assert job["modelVersion"]

        assert _check(api.get(f"/videos/{video_id}"), 200).json()["status"] == "analyzed"

        sets = _check(api.get(f"/videos/{video_id}/segment-sets"), 200).json()["items"]
        assert [s["kind"] for s in sets] == ["prediction"]
        prediction = sets[0]
        assert prediction["jobId"] == job["id"]
        assert prediction["segments"], "the worker produced no rallies for a video with rallies"

        predicted = [Rally(start_ms=s["startMs"], end_ms=s["endMs"]) for s in prediction["segments"]]
        metrics = evaluate(predicted, labels.rallies, labels.duration_ms)
        print(format_table(metrics))
        assert metrics.recall >= 0.6, metrics
        assert metrics.precision >= 0.6, metrics
        assert metrics.frame_accuracy >= 0.85, metrics
        # Padding and window quantization keep the boundaries about half a second off; more than a second
        # means the heuristic places rally starts or ends badly.
        assert metrics.boundary_mae_ms is not None
        assert metrics.boundary_mae_ms <= 1000, metrics

        final = _check(
            api.post(
                f"/videos/{video_id}/segment-sets",
                json={
                    "parentSetId": prediction["id"],
                    "segments": [
                        {"startMs": r.start_ms, "endMs": r.end_ms, "label": "rally", "confidence": None}
                        for r in labels.rallies
                    ],
                    "editLog": [],
                    "isFinal": True,
                },
            ),
            201,
        ).json()
        assert final["kind"] == "user"
        assert final["isFinal"] is True
        assert final["parentSetId"] == prediction["id"]

        sets = _check(api.get(f"/videos/{video_id}/segment-sets"), 200).json()["items"]
        assert [s["kind"] for s in sets] == ["prediction", "user"]
