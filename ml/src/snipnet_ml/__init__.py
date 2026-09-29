"""Rally detection for roundnet footage: features, models, evaluation and training."""

from importlib.metadata import version

from snipnet_ml.heuristic import HeuristicModel, HeuristicParams
from snipnet_ml.model import (
    Court,
    DummyModel,
    InvalidInputError,
    Point,
    Prediction,
    ProgressCallback,
    RallyModel,
    Roi,
    ScoreCurve,
    Segment,
    load_model,
)

__version__ = version("snipnet-ml")

__all__ = [
    "Court",
    "DummyModel",
    "HeuristicModel",
    "HeuristicParams",
    "InvalidInputError",
    "Point",
    "Prediction",
    "ProgressCallback",
    "RallyModel",
    "Roi",
    "ScoreCurve",
    "Segment",
    "__version__",
    "load_model",
]
