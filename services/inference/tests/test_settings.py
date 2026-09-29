import pytest

import snipnet_ml
from snipnet_inference.settings import Settings


def test_ml_package_is_available_to_the_worker() -> None:
    # The worker runs the models from snipnet_ml, so the workspace dependency must resolve.
    assert snipnet_ml.__version__


def test_from_env_applies_defaults() -> None:
    settings = Settings.from_env({"INTERNAL_TOKEN": "secret", "WORKER_ID": "worker-1"})

    assert settings == Settings(
        api_url="http://localhost:3000",
        internal_token="secret",
        worker_id="worker-1",
        poll_interval_s=2.0,
        model="heuristic",
    )


def test_from_env_normalizes_api_url_and_falls_back_to_hostname() -> None:
    settings = Settings.from_env({"INTERNAL_TOKEN": "secret", "API_URL": "http://api:3000/"})

    assert settings.api_url == "http://api:3000"
    assert settings.worker_id


@pytest.mark.parametrize("token", ["", "   "])
def test_from_env_requires_internal_token(token: str) -> None:
    with pytest.raises(ValueError, match="INTERNAL_TOKEN"):
        Settings.from_env({"INTERNAL_TOKEN": token})


@pytest.mark.parametrize("interval", ["abc", "0", "-1", "nan", "inf"])
def test_from_env_rejects_invalid_poll_interval(interval: str) -> None:
    with pytest.raises(ValueError, match="WORKER_POLL_INTERVAL_S"):
        Settings.from_env({"INTERNAL_TOKEN": "secret", "WORKER_POLL_INTERVAL_S": interval})
