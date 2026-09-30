from datetime import UTC, datetime
from uuid import uuid4

import pytest

from app.classifier import Recognition
from app.events import ImageReceived
from app.handler import handle
from app.image_source import ImageNotFoundError

IMAGE = b"\x89PNG fake image bytes"


class FakeImageSource:
    def __init__(self, images: dict[str, bytes]) -> None:
        self.images = images

    def get(self, key: str) -> bytes:
        if key not in self.images:
            raise ImageNotFoundError(key)
        return self.images[key]


class RecordingClassifier:
    def __init__(self, confidence: float) -> None:
        self.confidence = confidence
        self.seen: list[bytes] = []

    def classify(self, image_bytes: bytes) -> Recognition:
        self.seen.append(image_bytes)
        return Recognition(species="red deer", confidence=self.confidence)


def _event(key: str = "inbound/2026/09/15/a.png", hunter_id=None) -> ImageReceived:
    return ImageReceived(
        storage_key=key,
        hunter_id=hunter_id,
        event_id=uuid4(),
        occurred_at=datetime.now(UTC),
    )


def test_classifies_the_stored_image_and_links_back_to_the_source_event():
    event = _event(hunter_id=uuid4())
    classifier = RecordingClassifier(confidence=0.9)

    result = handle(event, FakeImageSource({event.storage_key: IMAGE}), classifier, threshold=0.7)

    assert classifier.seen == [IMAGE]  # the fetched bytes are what gets classified
    assert result.species == "red deer"
    assert result.low_confidence is False
    assert result.source_event_id == event.event_id
    assert result.hunter_id == event.hunter_id
    assert result.storage_key == event.storage_key
    assert result.event_id != event.event_id  # a new event, not a copy


def test_applies_the_confidence_threshold():
    event = _event()

    result = handle(
        event, FakeImageSource({event.storage_key: IMAGE}), RecordingClassifier(0.4), threshold=0.7
    )

    assert result.low_confidence is True


def test_missing_image_is_reported_not_classified():
    classifier = RecordingClassifier(confidence=0.9)

    with pytest.raises(ImageNotFoundError):
        handle(_event(), FakeImageSource({}), classifier, threshold=0.7)

    assert classifier.seen == []
