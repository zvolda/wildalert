"""The recognition use case, independent of how events arrive.

`handle` knows nothing about Kafka: the worker calls it for each consumed message today, and a
Pub/Sub push endpoint could call it later without changing this code.
"""

from app.classifier import Classifier, apply_threshold
from app.events import AnimalRecognized, ImageReceived
from app.image_source import ImageSource


def handle(
    event: ImageReceived,
    source: ImageSource,
    classifier: Classifier,
    threshold: float,
) -> AnimalRecognized:
    """Fetches the stored image, classifies it and applies the confidence threshold.

    Raises ImageNotFoundError when nothing is stored under the event's key.
    """
    image_bytes = source.get(event.storage_key)
    result = apply_threshold(classifier.classify(image_bytes), threshold)
    return AnimalRecognized(
        source_event_id=event.event_id,
        hunter_id=event.hunter_id,
        storage_key=event.storage_key,
        species=result.species,
        confidence=result.confidence,
        low_confidence=result.low_confidence,
    )
