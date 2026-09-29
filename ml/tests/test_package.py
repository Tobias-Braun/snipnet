import re

import snipnet_ml


def test_version_comes_from_installed_distribution() -> None:
    assert re.fullmatch(r"\d+\.\d+\.\d+.*", snipnet_ml.__version__)
