"""Worker configuration, read from environment variables documented in ``infra/.env.example``."""

import socket
from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    api_url: str
    internal_token: str
    worker_id: str
    poll_interval_s: float

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> "Settings":
        """Build settings from ``env``, failing fast on anything the worker cannot run without.

        ``INTERNAL_TOKEN`` has no default on purpose: a worker that silently talks to the API without a valid
        token would only fail later on every claim request.
        """
        token = env.get("INTERNAL_TOKEN", "").strip()
        if not token:
            raise ValueError("INTERNAL_TOKEN must be set")

        raw_interval = env.get("WORKER_POLL_INTERVAL_S", "2")
        try:
            poll_interval_s = float(raw_interval)
        except ValueError:
            raise ValueError(f"WORKER_POLL_INTERVAL_S must be a number, got {raw_interval!r}") from None
        if poll_interval_s <= 0:
            raise ValueError(f"WORKER_POLL_INTERVAL_S must be positive, got {raw_interval!r}")

        return cls(
            api_url=env.get("API_URL", "http://localhost:3000").rstrip("/"),
            internal_token=token,
            worker_id=env.get("WORKER_ID") or socket.gethostname(),
            poll_interval_s=poll_interval_s,
        )
