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
from snipnet_ml import Court, InvalidInputError, Prediction, RallyModel
from snipnet_ml.court_detect import CourtSuggestion, detect_court

log = logging.getLogger(__name__)

# Progress is forwarded at most this often; the API only needs it to extend the lease and drive a progress bar.
PROGRESS_INTERVAL_S = 1.0
# Downloads may take minutes, but a stalled connection should not hang the worker forever.
HTTP_TIMEOUT = httpx.Timeout(30.0, read=120.0)
# The error text is stored on the job and shown to the user, while model exception messages can be arbitrarily long.
MAX_ERROR_LENGTH = 1000


class ProxyDownloadError(Exception):
    """Downloading the proxy failed. The message deliberately omits the presigned URL and its signature."""


class LeaseLostError(Exception):
    """The API answered 409: the job is no longer ours (lease expired and another worker reclaimed it)."""


def parse_court(raw: dict[str, Any] | None) -> Court | None:
    if not raw:
        return None
    return Court.model_validate(raw)


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


def court_suggestion_body(suggestion: CourtSuggestion | None) -> dict[str, Any] | None:
    """Serialize a detection into the ``POST /internal/videos/:id/court-suggestion`` body, ``None`` for no net."""
    if suggestion is None:
        return None
    return {
        "court": suggestion.court.model_dump(by_alias=True),
        "confidence": min(1.0, max(0.0, suggestion.confidence)),
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
                worked = self.run_pending()
            except httpx.HTTPError as exc:
                # The API being down or restarting is routine; back off like on an empty queue.
                log.warning("API request failed: %s", exc)
                worked = False
            except Exception:
                # A malformed claim response must not kill the worker; the claimed job's lease simply expires.
                log.exception("unexpected error while claiming a job")
                worked = False
            if not worked:
                self._stop.wait(self._settings.poll_interval_s)

    def run_pending(self) -> bool:
        """Process at most one detection task or, if there is none, one analysis job. Returns whether work was done.

        Court detection goes first: it takes seconds and a user is waiting for the pre-filled court before they can
        start the (much longer) analysis, so it should never queue up behind analysis jobs.
        """
        return self.run_court_detection_once() or self.run_once()

    def run_court_detection_once(self) -> bool:
        """Claim and process at most one court detection task. Returns whether a task was claimed."""
        response = self._client.post(
            f"{self._settings.api_url}/internal/court-detection/claim",
            json={"workerId": self._settings.worker_id},
            headers=self._headers(),
        )
        if response.status_code == 204:
            return False
        response.raise_for_status()
        claim = response.json()
        self._process_court_detection(claim["videoId"], claim["proxyUrl"])
        return True

    def _process_court_detection(self, video_id: str, proxy_url: str) -> None:
        log.info("claimed court detection for video %s", video_id)
        base = f"{self._settings.api_url}/internal/videos/{video_id}/court-suggestion"
        try:
            with tempfile.TemporaryDirectory(prefix="snipnet-") as tmp:
                proxy_path = Path(tmp) / "proxy.mp4"
                self._download(proxy_url, proxy_path)
                suggestion = detect_court(proxy_path)
            # A JSON `null` body reports that the detection ran and found no net.
            self._post_url(base, court_suggestion_body(suggestion))
            log.info("court detection for video %s done", video_id)
        except Exception as exc:
            log.exception("court detection for video %s failed", video_id)
            retryable = not isinstance(exc, InvalidInputError)
            error = (str(exc) or type(exc).__name__)[:MAX_ERROR_LENGTH]
            try:
                self._post_url(f"{base}/fail", {"error": error, "retryable": retryable})
            except (LeaseLostError, httpx.HTTPError) as post_exc:
                # The lease expires and the task is claimed again, or the video is gone.
                log.warning("could not report failure of court detection for video %s: %s", video_id, post_exc)

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
            self._post(job_id, "result", {"workerId": self._settings.worker_id, **prediction_body(prediction)})
            log.info("job %s succeeded", job_id)
        except LeaseLostError:
            # Another worker owns the job now, so reporting a failure would disturb its attempt. Just drop the job.
            log.warning("job %s was reclaimed by another worker, dropping it", job_id)
        except Exception as exc:
            log.exception("job %s failed", job_id)
            self._report_failure(job_id, exc)

    def _download(self, url: str, dest: Path) -> None:
        # The proxy URL is presigned, so it must not receive the internal token.
        try:
            with self._client.stream("GET", url) as response:
                if response.is_error:
                    raise ProxyDownloadError(f"proxy download failed with HTTP {response.status_code}")
                with dest.open("wb") as out:
                    for chunk in response.iter_bytes(1024 * 1024):
                        out.write(chunk)
        except httpx.HTTPError as exc:
            # httpx messages can embed the request URL including its signature, so the original exception is
            # not chained into the logs or into the error reported to the API.
            raise ProxyDownloadError(f"proxy download failed: {type(exc).__name__}") from None

    def _progress_reporter(self, job_id: str) -> Callable[[float], None]:
        last_sent: float | None = None

        def report(fraction: float) -> None:
            nonlocal last_sent
            now = self._clock()
            if last_sent is not None and now - last_sent < PROGRESS_INTERVAL_S:
                return
            last_sent = now
            try:
                self._post(
                    job_id, "progress", {"workerId": self._settings.worker_id, "progress": min(1.0, max(0.0, fraction))}
                )
            except LeaseLostError:
                # Propagates through the model so the run is aborted early instead of burning compute.
                raise
            except httpx.HTTPError as exc:
                # A lost progress update only delays the lease extension; it must not abort the analysis.
                log.warning("progress update for job %s failed: %s", job_id, exc)

        return report

    def _report_failure(self, job_id: str, exc: Exception) -> None:
        retryable = not isinstance(exc, InvalidInputError)
        try:
            error = (str(exc) or type(exc).__name__)[:MAX_ERROR_LENGTH]
            self._post(job_id, "fail", {"workerId": self._settings.worker_id, "error": error, "retryable": retryable})
        except LeaseLostError:
            log.warning("job %s was reclaimed before its failure could be reported", job_id)
        except httpx.HTTPError as post_exc:
            # If even the failure report is lost, the lease expires and the job is claimed again.
            log.warning("could not report failure of job %s: %s", job_id, post_exc)

    def _post(self, job_id: str, action: str, body: dict[str, Any]) -> None:
        self._post_url(self._job_url(job_id, action), body, what=f"{action} for job {job_id}")

    def _post_url(self, url: str, body: dict[str, Any] | None, what: str | None = None) -> None:
        if body is None:
            # httpx treats `json=None` as "no body", but the API expects a literal JSON `null` here.
            response = self._client.post(
                url, content=b"null", headers={**self._headers(), "Content-Type": "application/json"}
            )
        else:
            response = self._client.post(url, json=body, headers=self._headers())
        if response.status_code == 409:
            raise LeaseLostError(f"{what or url} rejected with 409")
        response.raise_for_status()
