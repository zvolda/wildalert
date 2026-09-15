"""Service configuration, read from environment variables with sensible defaults."""

import os
from functools import lru_cache

from pydantic import BaseModel, Field


class Settings(BaseModel):
    # Below this confidence we flag a result as low-confidence rather than asserting a species,
    # so we never text a hunter a wild guess (the precision safeguard from the roadmap).
    confidence_threshold: float = Field(default=0.7, ge=0.0, le=1.0)

    # Which classifier to use: "stub" (fake, default — no ML deps) or "deepfaune" (real model,
    # needs torch + PytorchWildlife, so only the container image sets this).
    classifier: str = "stub"


@lru_cache
def get_settings() -> Settings:
    kwargs = {}
    threshold = os.getenv("RECOGNITION_CONFIDENCE_THRESHOLD")
    if threshold is not None:
        kwargs["confidence_threshold"] = float(threshold)
    classifier = os.getenv("RECOGNITION_CLASSIFIER")
    if classifier:
        kwargs["classifier"] = classifier
    return Settings(**kwargs)
