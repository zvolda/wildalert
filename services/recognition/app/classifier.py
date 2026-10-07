"""Animal classification behind a swappable interface.

Everything talks to the `Classifier` protocol, so we can build and test the API now with a
stub and drop in a real model (SpeciesNet or DeepFaune) later without touching callers — the
same fake-first pattern the Kotlin services use (ImageStore, SmsSender, EventPublisher).
"""

from functools import lru_cache
from typing import Protocol

from pydantic import BaseModel, Field

from app.config import get_settings


class Recognition(BaseModel):
    """The result of classifying one image."""

    species: str
    confidence: float = Field(ge=0.0, le=1.0)


class RecognitionResult(Recognition):
    """Classifier output plus the threshold decision returned to callers."""

    low_confidence: bool


def apply_threshold(result: Recognition, threshold: float) -> RecognitionResult:
    """Flags the result as low-confidence when it falls below the threshold. This is policy,
    kept out of the classifier so the model only reports what it sees, not what we do about it."""
    return RecognitionResult(
        species=result.species,
        confidence=result.confidence,
        low_confidence=result.confidence < threshold,
    )


class Classifier(Protocol):
    """Classifies raw image bytes into a species + confidence."""

    def classify(self, image_bytes: bytes) -> Recognition: ...


class StubClassifier:
    """Fake classifier returning a fixed result, so the endpoint and its contract exist
    before a real model is chosen and downloaded. Ignores the image bytes entirely."""

    def classify(self, image_bytes: bytes) -> Recognition:
        return Recognition(species="wild boar", confidence=0.97)


@lru_cache
def _load_deepfaune() -> Classifier:
    """Loads the DeepFaune model once (weights load is expensive) and reuses it."""
    from app.deepfaune import DeepFauneClassifier

    return DeepFauneClassifier()


def get_classifier() -> Classifier:
    """Provides the configured classifier: the real DeepFaune model or the fake stub. Shared by
    the HTTP API and the Kafka worker."""
    if get_settings().classifier == "deepfaune":
        return _load_deepfaune()
    return StubClassifier()
