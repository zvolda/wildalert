"""Service configuration, read from environment variables with sensible defaults."""

import os
from functools import lru_cache

from pydantic import BaseModel, Field


class Settings(BaseModel):
    # Below this confidence we flag a result as low-confidence rather than asserting a species,
    # so we never text a hunter a wild guess (the precision safeguard from the roadmap).
    confidence_threshold: float = Field(default=0.7, ge=0.0, le=1.0)


@lru_cache
def get_settings() -> Settings:
    raw = os.getenv("RECOGNITION_CONFIDENCE_THRESHOLD")
    if raw is not None:
        return Settings(confidence_threshold=float(raw))
    return Settings()
