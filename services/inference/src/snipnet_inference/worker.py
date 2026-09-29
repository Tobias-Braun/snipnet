"""Job loop: claim a job, download its proxy, run the model and report the outcome to the internal API."""

import logging
import tempfile
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

from snipnet_inference.settings import Settings
from snipnet_ml import Court, InvalidInputError, Point, Prediction, RallyModel, Roi

log = logging.getLogger(__name__)

# Progress is forwarded at most this often; the API only needs it to extend the lease and drive a progress bar.
PROGRESS_INTERVAL_S = 1.0
# Downloads may take minutes, but a stalled connection should not hang the worker forever.
HTTP_TIMEOUT = httpx.Timeout(30.0, read=120.0)


def parse_court(raw: dict[str, Any] | None) -> Court | None:
    if not raw:
        return None
    return Court(roi=Roi(**raw["roi"]), net_point=Point(**raw["netPoint"]))


def prediction_body(prediction: Prediction) -> dict[str, Any]:
    """Serialize a prediction into the ``POST /internal/jobs/:id/result`` body of docs/api.md."""
    scores = prediction.scores
    return {
        "modelVersion": prediction.model_version,
        "segments": [
            {"startMs": s.start_ms, "endMs": s.end_ms, "label": "rally", "confidence": s.confidence}
            for s in prediction.segments
        ],
        "scores": None if scores is None else {"hz": scores.hz, "values": scores.values},
    }


class Worker:
    def __init__(
        self,
        settings: Settings,
        model: RallyModel,
        client: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._settings = settings
        self._model = model
        self._clock = clock
        self._client = client or httpx.Client(timeout=HTTP_TIMEOUT)
        self._stop = threading.Event()

    def stop(self) -> None:
        """Ask the loop to exit once the job in flight (if any) is finished."""
        self._stop.set()

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                worked = self.run_once()
            except httpx.HTTPError as exc:
                # The API being down or restarting is routine; back off like on an empty queue.
                log.warning("API request failed: %s", exc)
                worked = False
            if not worked:
                self._stop.wait(self._settings.poll_interval_s)

    def run_once(self) -> bool:
        """Claim and process at most one job. Returns whether a job was claimed."""
        response = self._client.post(
            f"{self._settings.api_url}/internal/jobs/claim",
            json={"workerId": self._settings.worker_id},
            headers=self._headers(),
        )
        if response.status_code == 204:
            return False
        response.raise_for_status()
        claim = response.json()
        self._process(claim["job"]["id"], claim["video"], claim["proxyUrl"])
        return True

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._settings.internal_token}"}

    def _job_url(self, job_id: str, action: str) -> str:
        return f"{self._settings.api_url}/internal/jobs/{job_id}/{action}"

    def _process(self, job_id: str, video: dict[str, Any], proxy_url: str) -> None:
        log.info("claimed job %s", job_id)
        try:
            # The directory is removed on every exit path, including failures halfway through the download.
            with tempfile.TemporaryDirectory(prefix="snipnet-") as tmp:
                proxy_path = Path(tmp) / "proxy.mp4"
                self._download(proxy_url, proxy_path)
                prediction = self._model.predict(
                    proxy_path, parse_court(video.get("court")), self._progress_reporter(job_id)
                )
            self._post(job_id, "result", prediction_body(prediction))
            log.info("job %s succeeded", job_id)
        except Exception as exc:
            log.exception("job %s failed", job_id)
            self._report_failure(job_id, exc)

    def _download(self, url: str, dest: Path) -> None:
        # The proxy URL is presigned, so it must not receive the internal token.
        with self._client.stream("GET", url) as response:
            response.raise_for_status()
            with dest.open("wb") as out:
                for chunk in response.iter_bytes(1024 * 1024):
                    out.write(chunk)

    def _progress_reporter(self, job_id: str) -> Callable[[float], None]:
        last_sent: float | None = None

        def report(fraction: float) -> None:
            nonlocal last_sent
            now = self._clock()
            if last_sent is not None and now - last_sent < PROGRESS_INTERVAL_S:
                return
            last_sent = now
            try:
                self._post(job_id, "progress", {"progress": min(1.0, max(0.0, fraction))})
            except httpx.HTTPError as exc:
                # A lost progress update only delays the lease extension; it must not abort the analysis.
                log.warning("progress update for job %s failed: %s", job_id, exc)

        return report

    def _report_failure(self, job_id: str, exc: Exception) -> None:
        retryable = not isinstance(exc, InvalidInputError)
        try:
            self._post(job_id, "fail", {"error": str(exc) or type(exc).__name__, "retryable": retryable})
        except httpx.HTTPError as post_exc:
            # If even the failure report is lost, the lease expires and the job is claimed again.
            log.warning("could not report failure of job %s: %s", job_id, post_exc)

    def _post(self, job_id: str, action: str, body: dict[str, Any]) -> None:
        response = self._client.post(self._job_url(job_id, action), json=body, headers=self._headers())
        response.raise_for_status()
