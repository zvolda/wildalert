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

    # Where stored images are read from: "local" (default — a folder shared with
    # email-ingestion's filesystem store) or "r2" (Cloudflare R2).
    image_source: str = "local"
    # Folder for the local source. Must be the same folder email-ingestion writes to.
    image_root: str = "data/images"

    # Cloudflare R2 (S3 API) — same variable names email-ingestion uses.
    r2_endpoint: str = ""
    r2_bucket: str = ""
    r2_access_key: str = ""
    r2_secret_key: str = ""
    r2_region: str = "auto"

    # How recognition results are published: "logging" (fake, default) or "pubsub".
    events_provider: str = "logging"
    animal_recognized_topic: str = "animal.recognized"
    # Required when events_provider=pubsub. Locally this is the emulator's project.
    pubsub_project_id: str = ""


# Plain string settings and the environment variable each one is read from.
_STRING_ENV_VARS = {
    "classifier": "RECOGNITION_CLASSIFIER",
    "image_source": "RECOGNITION_IMAGE_SOURCE",
    "image_root": "RECOGNITION_IMAGE_ROOT",
    "r2_endpoint": "R2_ENDPOINT",
    "r2_bucket": "R2_BUCKET",
    "r2_access_key": "R2_ACCESS_KEY",
    "r2_secret_key": "R2_SECRET_KEY",
    "r2_region": "R2_REGION",
    "events_provider": "RECOGNITION_EVENTS_PROVIDER",
    "animal_recognized_topic": "ANIMAL_RECOGNIZED_TOPIC",
    "pubsub_project_id": "PUBSUB_PROJECT_ID",
}

# Numeric settings, parsed with the matching type.
_NUMBER_ENV_VARS = {
    "confidence_threshold": ("RECOGNITION_CONFIDENCE_THRESHOLD", float),
}


@lru_cache
def get_settings() -> Settings:
    kwargs = {}
    # Cloud Run sets GOOGLE_CLOUD_PROJECT; PUBSUB_PROJECT_ID (below) wins if both are set.
    project = os.getenv("GOOGLE_CLOUD_PROJECT")
    if project:
        kwargs["pubsub_project_id"] = project
    for field, (env_var, parse) in _NUMBER_ENV_VARS.items():
        value = os.getenv(env_var)
        if value:
            kwargs[field] = parse(value)
    for field, env_var in _STRING_ENV_VARS.items():
        value = os.getenv(env_var)
        if value:
            kwargs[field] = value
    return Settings(**kwargs)
