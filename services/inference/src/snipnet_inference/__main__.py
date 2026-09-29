"""Entry point: ``python -m snipnet_inference``."""

import logging
import os
import signal

from snipnet_inference.settings import Settings
from snipnet_inference.worker import Worker
from snipnet_ml import load_model


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(), format="%(asctime)s %(levelname)s %(message)s"
    )
    settings = Settings.from_env(os.environ)
    worker = Worker(settings, load_model(settings.model))
    # Docker sends SIGTERM on stop; finishing the current job first avoids leaving it to the lease timeout.
    signal.signal(signal.SIGTERM, lambda *_: worker.stop())
    signal.signal(signal.SIGINT, lambda *_: worker.stop())
    logging.getLogger(__name__).info("worker %s started with model %s", settings.worker_id, settings.model)
    worker.run()


if __name__ == "__main__":
    main()
