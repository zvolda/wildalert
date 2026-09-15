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

    # Kafka worker (python -m app.worker). Host apps reach the compose broker on 29092;
    # containers on the compose network use kafka:9092.
    kafka_bootstrap_servers: str = "localhost:29092"
    image_received_topic: str = "image.received"
    animal_recognized_topic: str = "animal.recognized"
    # Consumer group: all worker replicas share it, so each event is handled by one of them.
    consumer_group: str = "recognition"


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
    "kafka_bootstrap_servers": "KAFKA_BOOTSTRAP_SERVERS",
    "image_received_topic": "IMAGE_RECEIVED_TOPIC",
    "animal_recognized_topic": "ANIMAL_RECOGNIZED_TOPIC",
    "consumer_group": "RECOGNITION_CONSUMER_GROUP",
}


@lru_cache
def get_settings() -> Settings:
    kwargs = {}
    threshold = os.getenv("RECOGNITION_CONFIDENCE_THRESHOLD")
    if threshold is not None:
        kwargs["confidence_threshold"] = float(threshold)
    for field, env_var in _STRING_ENV_VARS.items():
        value = os.getenv(env_var)
        if value:
            kwargs[field] = value
    return Settings(**kwargs)
