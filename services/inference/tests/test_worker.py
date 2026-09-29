import json
from pathlib import Path

import httpx
import pytest
import respx

from snipnet_inference.settings import Settings
from snipnet_inference.worker import Worker
from snipnet_ml import Court, InvalidInputError, Prediction, ProgressCallback, ScoreCurve, Segment

API = "http://api.test"
PROXY_URL = "http://storage.test/proxy.mp4"
JOB_ID = "11111111-1111-4111-8111-111111111111"
CLAIM = {
    "job": {"id": JOB_ID, "status": "running"},
    "video": {
        "id": "v1",
        "court": {"roi": {"x": 0.1, "y": 0.2, "width": 0.5, "height": 0.6}, "netPoint": {"x": 0.4, "y": 0.5}},
    },
    "proxyUrl": PROXY_URL,
}


class RecordingModel:
    """Model stub that records what it was given and either returns a prediction or raises ``error``."""

    version = "stub-v1"

    def __init__(self, error: Exception | None = None, progress_calls: int = 0) -> None:
        self.error = error
        self.progress_calls = progress_calls
        self.video_path: Path | None = None
        self.video_bytes: bytes | None = None
        self.court: Court | None = None

    def predict(self, video_path: Path, court: Court | None, progress: ProgressCallback) -> Prediction:
        self.video_path = video_path
        self.video_bytes = video_path.read_bytes()
        self.court = court
        for i in range(self.progress_calls):
            progress(i / self.progress_calls)
        if self.error:
            raise self.error
        return Prediction([Segment(1000, 2000, 0.9)], ScoreCurve(1.0, [0.0, 1.0]), self.version)


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        self.now += 0.3
        return self.now


def make_worker(model: RecordingModel, client: httpx.Client, poll: float = 0.01) -> Worker:
    settings = Settings(api_url=API, internal_token="tok", worker_id="w1", poll_interval_s=poll, model="stub-v1")
    return Worker(settings, model, client=client, clock=FakeClock())


@pytest.fixture
def api():
    with respx.mock(assert_all_called=False) as mock:
        yield mock


def test_success_downloads_proxy_runs_model_and_posts_result(api: respx.MockRouter) -> None:
    claim = api.post(f"{API}/internal/jobs/claim").respond(200, json=CLAIM)
    api.get(PROXY_URL).respond(200, content=b"video-bytes")
    progress = api.post(f"{API}/internal/jobs/{JOB_ID}/progress").respond(204)
    result = api.post(f"{API}/internal/jobs/{JOB_ID}/result").respond(204)
    model = RecordingModel(progress_calls=10)

    assert make_worker(model, httpx.Client()).run_once() is True

    assert json.loads(claim.calls[0].request.content) == {"workerId": "w1"}
    assert claim.calls[0].request.headers["Authorization"] == "Bearer tok"
    assert "Authorization" not in api.calls[1].request.headers
    assert model.video_bytes == b"video-bytes"
    assert model.court is not None
    assert model.court.roi.width == 0.5
    assert model.court.net_point.y == 0.5
    assert json.loads(result.calls[0].request.content) == {
        "modelVersion": "stub-v1",
        "segments": [{"startMs": 1000, "endMs": 2000, "label": "rally", "confidence": 0.9}],
        "scores": {"hz": 1.0, "values": [0.0, 1.0]},
    }
    # The fake clock advances 0.3 s per call, so 10 reports collapse to roughly one per second.
    assert 1 <= progress.call_count <= 4
    assert model.video_path is not None
    assert not model.video_path.parent.exists()


@pytest.mark.parametrize(
    ("error", "retryable"),
    [(RuntimeError("boom"), True), (InvalidInputError("bad video"), False)],
)
def test_model_failure_posts_fail_and_cleans_up(api: respx.MockRouter, error: Exception, retryable: bool) -> None:
    api.post(f"{API}/internal/jobs/claim").respond(200, json=CLAIM)
    api.get(PROXY_URL).respond(200, content=b"x")
    fail = api.post(f"{API}/internal/jobs/{JOB_ID}/fail").respond(204)
    result = api.post(f"{API}/internal/jobs/{JOB_ID}/result").respond(204)
    model = RecordingModel(error=error)

    assert make_worker(model, httpx.Client()).run_once() is True

    assert json.loads(fail.calls[0].request.content) == {"error": str(error), "retryable": retryable}
    assert not result.called
    assert model.video_path is not None
    assert not model.video_path.parent.exists()


def test_download_failure_is_reported_as_retryable(api: respx.MockRouter) -> None:
    api.post(f"{API}/internal/jobs/claim").respond(200, json=CLAIM)
    api.get(PROXY_URL).respond(403)
    fail = api.post(f"{API}/internal/jobs/{JOB_ID}/fail").respond(204)

    make_worker(RecordingModel(), httpx.Client()).run_once()

    assert json.loads(fail.calls[0].request.content)["retryable"] is True


def test_empty_queue_claims_nothing(api: respx.MockRouter) -> None:
    api.post(f"{API}/internal/jobs/claim").respond(204)
    model = RecordingModel()

    assert make_worker(model, httpx.Client()).run_once() is False

    assert model.video_path is None
    assert api.calls.call_count == 1


def test_run_survives_api_errors_and_stops_on_request(api: respx.MockRouter) -> None:
    worker = make_worker(RecordingModel(), httpx.Client())
    calls = 0

    def claim(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls >= 3:
            worker.stop()
        if calls == 1:
            raise httpx.ConnectError("api down")
        return httpx.Response(204)

    api.post(f"{API}/internal/jobs/claim").mock(side_effect=claim)

    worker.run()

    assert calls == 3
