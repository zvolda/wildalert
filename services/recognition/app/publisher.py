"""Publishing AnimalRecognized, behind an interface so the service runs with no broker.

Same swappable-interface pattern as `Classifier` and `ImageSource`: a fake that logs is the
default, and the real Cloud Pub/Sub implementation is selected by configuration
(RECOGNITION_EVENTS_PROVIDER=pubsub).
"""

import logging
from functools import lru_cache
from typing import Protocol

from app.config import Settings, get_settings
from app.events import AnimalRecognized

log = logging.getLogger("recognition.publisher")


class EventPublisher(Protocol):
    """Publishes a recognition result. Raises if the result could not be published."""

    def publish(self, event: AnimalRecognized) -> None: ...


class LoggingEventPublisher:
    """Fake publisher that only logs. Active by default, so nothing needs a broker locally."""

    def publish(self, event: AnimalRecognized) -> None:
        log.info(
            "[FAKE EVENT] AnimalRecognized id=%s source=%s species=%s confidence=%.2f low=%s",
            event.event_id,
            event.source_event_id,
            event.species,
            event.confidence,
            event.low_confidence,
        )


class PubSubEventPublisher:
    """Publishes to a Cloud Pub/Sub topic.

    Blocks until Pub/Sub confirms the write: if publishing fails, the exception propagates out of
    the push endpoint as a 500, Pub/Sub redelivers the ImageReceived and the image is classified
    again. Returning 2xx for a result nobody received would lose the hunter's alert silently.

    The google-cloud-pubsub client honours PUBSUB_EMULATOR_HOST, so local dev needs no credentials
    and no code path of its own.
    """

    def __init__(self, project_id: str, topic: str, delivery_timeout: float = 30.0) -> None:
        # Imported here, not at module level, so the fake publisher needs no GCP client at all.
        from google.cloud import pubsub_v1

        self._client = pubsub_v1.PublisherClient()
        self._topic_path = self._client.topic_path(project_id, topic)
        self._delivery_timeout = delivery_timeout

    def publish(self, event: AnimalRecognized) -> None:
        future = self._client.publish(self._topic_path, event.to_json().encode("utf-8"))
        message_id = future.result(timeout=self._delivery_timeout)
        log.info(
            "Published AnimalRecognized id=%s source=%s species=%s confidence=%.2f low=%s"
            " to %s (messageId=%s)",
            event.event_id,
            event.source_event_id,
            event.species,
            event.confidence,
            event.low_confidence,
            self._topic_path,
            message_id,
        )


def build_event_publisher(settings: Settings) -> EventPublisher:
    """Creates the configured publisher: the logging fake (default) or real Pub/Sub."""
    if settings.events_provider == "pubsub":
        if not settings.pubsub_project_id:
            raise ValueError(
                "RECOGNITION_EVENTS_PROVIDER=pubsub needs a project: set PUBSUB_PROJECT_ID "
                "(or GOOGLE_CLOUD_PROJECT)"
            )
        return PubSubEventPublisher(
            settings.pubsub_project_id, settings.animal_recognized_topic
        )
    return LoggingEventPublisher()


@lru_cache
def get_event_publisher() -> EventPublisher:
    """FastAPI dependency; cached so one client is reused. Tests override it with a fake."""
    return build_event_publisher(get_settings())
