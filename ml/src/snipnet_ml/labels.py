"""Label format shared by the fixture generator, the evaluation and (later) the training pipeline.

A label file is a rally list in the same shape as a final user segment set of the API (`startMs`/`endMs`), plus
the video metadata needed to evaluate frame-level accuracy. JSON fields are camelCase like the rest of the API
contract in `docs/api.md`; the Python attributes are snake_case.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class _CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class Roi(_CamelModel):
    """Court region of interest, normalized to the video frame with the origin at the top left."""

    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)


class Point(_CamelModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)


class Court(_CamelModel):
    roi: Roi
    net_point: Point


class Rally(_CamelModel):
    start_ms: int = Field(ge=0)
    end_ms: int

    @model_validator(mode="after")
    def _check_order(self) -> Rally:
        if self.end_ms <= self.start_ms:
            raise ValueError("end_ms must be greater than start_ms")
        return self


class Labels(_CamelModel):
    video: str
    duration_ms: int = Field(gt=0)
    court: Court
    rallies: list[Rally]

    @model_validator(mode="after")
    def _check_rallies(self) -> Labels:
        previous_end = 0
        for rally in self.rallies:
            if rally.start_ms < previous_end:
                raise ValueError("rallies must be sorted by start and must not overlap")
            if rally.end_ms > self.duration_ms:
                raise ValueError("rally ends after the video duration")
            previous_end = rally.end_ms
        return self


def load_labels(path: str | Path) -> Labels:
    return Labels.model_validate_json(Path(path).read_text(encoding="utf-8"))


def save_labels(labels: Labels, path: str | Path) -> None:
    Path(path).write_text(labels.model_dump_json(by_alias=True, indent=2) + "\n", encoding="utf-8")
