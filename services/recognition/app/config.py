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

    # What classifying one photo costs the hunter, in CZK minor units (haléře). It travels to
    # user-account on every charge, because this service is the one that knows what a recognition
    # costs — user-account charges what it is told.
    #
    # A default is safe here and 100 is the documented price: unlike the signup grant, forgetting
    # the variable must charge the normal amount rather than run the model for free. A 0 would be
    # rejected by the endpoint's own @Min(1), so a missing price fails loudly. Pinned by a test.
    recognition_cost_minor: int = Field(default=100, ge=1)

    # Base URL of user-account, which holds the balance and the hunter's identity.
    user_account_base_url: str = "http://localhost:8081"
    # "none" (default) or "gcp-id-token". In the cloud user-account runs with Cloud Run's
    # "Require authentication", so calls need a Google ID token whose audience is the base URL;
    # set USER_ACCOUNT_AUTH=gcp-id-token there. Locally there is nothing to authenticate to.
    user_account_auth: str = "none"

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
    # Same variable names notification uses, so the two charging services are configured alike.
    "user_account_base_url": "USER_ACCOUNT_BASE_URL",
    "user_account_auth": "USER_ACCOUNT_AUTH",
    "events_provider": "RECOGNITION_EVENTS_PROVIDER",
    "animal_recognized_topic": "ANIMAL_RECOGNIZED_TOPIC",
    "pubsub_project_id": "PUBSUB_PROJECT_ID",
}

# Numeric settings, parsed with the matching type.
_NUMBER_ENV_VARS = {
    "confidence_threshold": ("RECOGNITION_CONFIDENCE_THRESHOLD", float),
    "recognition_cost_minor": ("RECOGNITION_COST_MINOR", int),
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
