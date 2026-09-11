"""Animal classification behind a swappable interface.

Everything talks to the `Classifier` protocol, so we can build and test the API now with a
stub and drop in a real model (SpeciesNet or DeepFaune) later without touching callers — the
same fake-first pattern the Kotlin services use (ImageStore, SmsSender, EventPublisher).
"""

from typing import Protocol

from pydantic import BaseModel, Field


class Recognition(BaseModel):
    """The result of classifying one image."""

    species: str
    confidence: float = Field(ge=0.0, le=1.0)


class Classifier(Protocol):
    """Classifies raw image bytes into a species + confidence."""

    def classify(self, image_bytes: bytes) -> Recognition: ...


class StubClassifier:
    """Fake classifier returning a fixed result, so the endpoint and its contract exist
    before a real model is chosen and downloaded. Ignores the image bytes entirely."""

    def classify(self, image_bytes: bytes) -> Recognition:
        return Recognition(species="wild boar", confidence=0.97)
