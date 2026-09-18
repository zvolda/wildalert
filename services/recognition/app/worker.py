"""Kafka worker: consumes `image.received`, runs recognition, publishes `animal.recognized`.

Run with `python -m app.worker` (same image as the HTTP API, different command). This is a thin
transport adapter — the actual work is `app.handler.handle`.

Delivery is **at-least-once**: a message's offset is committed only after its result has been
delivered to Kafka. If the worker dies in between, the message is redelivered and handled again —
so consumers of `animal.recognized` must tolerate duplicates (that is what `sourceEventId` is for).

Failure policy:
- A message that can never succeed (invalid JSON / schema, or no image under its key) goes straight
  to the dead-letter topic — retrying it would only block the partition.
- Anything else (storage hiccup, model failure) is retried a few times with a growing pause; if it
  still fails, the message goes to the dead-letter topic so the queue keeps moving.
- Only a failure to reach Kafka itself is raised: the worker exits without committing and retries
  the message after a restart. (Trade-off: a long storage outage sends a backlog to the dead-letter
  topic rather than waiting it out. Replaying that topic is a later slice.)

Dead-lettered messages keep their original bytes and key, with the reason in a `reason` header.
"""

import logging
import signal
import time
from collections.abc import Callable
from typing import Protocol

from confluent_kafka import Consumer, KafkaException, Producer
from pydantic import ValidationError

from app.classifier import get_classifier
from app.config import Settings, get_settings
from app.events import AnimalRecognized, ImageReceived
from app.handler import handle
from app.image_source import ImageNotFoundError, build_image_source

log = logging.getLogger("recognition.worker")

HandleFn = Callable[[ImageReceived], AnimalRecognized]


class MessageConsumer(Protocol):
    """The slice of confluent_kafka.Consumer the worker uses (lets tests pass a fake)."""

    def poll(self, timeout: float): ...

    def commit(self, message, asynchronous: bool) -> None: ...


class MessageProducer(Protocol):
    """The slice of confluent_kafka.Producer the worker uses (lets tests pass a fake)."""

    def produce(self, topic: str, value, key, on_delivery, headers=None) -> None: ...

    def flush(self, timeout: float) -> int: ...


class Undeliverable(Exception):
    """Raised for a message that can never succeed, so it is dead-lettered without retrying."""


class RecognitionWorker:
    def __init__(
        self,
        consumer: MessageConsumer,
        producer: MessageProducer,
        handle_fn: HandleFn,
        output_topic: str,
        dead_letter_topic: str,
        max_attempts: int = 3,
        retry_backoff_seconds: float = 2.0,
        delivery_timeout: float = 30.0,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._consumer = consumer
        self._producer = producer
        self._handle_fn = handle_fn
        self._output_topic = output_topic
        self._dead_letter_topic = dead_letter_topic
        self._max_attempts = max_attempts
        self._retry_backoff_seconds = retry_backoff_seconds
        self._delivery_timeout = delivery_timeout
        self._sleep = sleep

    def poll_once(self, timeout: float = 1.0) -> None:
        """Processes at most one message, then commits it as done (handled or dead-lettered)."""
        message = self._consumer.poll(timeout)
        if message is None:
            return
        if message.error():
            # Transient client/broker conditions; librdkafka keeps retrying underneath.
            log.warning("Kafka consumer error: %s", message.error())
            return

        self._process(message)
        self._consumer.commit(message=message, asynchronous=False)

    def _process(self, message) -> None:
        try:
            event = ImageReceived.model_validate_json(message.value() or b"")
        except ValidationError as e:
            self._dead_letter(message, f"invalid ImageReceived: {e}")
            return

        for attempt in range(1, self._max_attempts + 1):
            try:
                self._publish(self._handle_fn(event))
                return
            except ImageNotFoundError as e:
                self._dead_letter(message, str(e))
                return
            except Undeliverable as e:
                self._dead_letter(message, str(e))
                return
            except KafkaException:
                raise  # can't reach Kafka: don't commit, let a restart retry the message
            except Exception as e:
                if attempt == self._max_attempts:
                    self._dead_letter(message, f"failed after {attempt} attempts: {e!r}")
                    return
                pause = self._retry_backoff_seconds * attempt
                log.warning(
                    "Attempt %d/%d failed for ImageReceived id=%s (%r); retrying in %.1fs",
                    attempt, self._max_attempts, event.event_id, e, pause,
                )
                self._sleep(pause)

    def _publish(self, result: AnimalRecognized) -> None:
        # Keyed like email-ingestion: by hunter, so one hunter's events stay ordered.
        key = str(result.hunter_id or result.event_id)
        self._send(self._output_topic, result.to_json(), key)
        log.info(
            "Published AnimalRecognized id=%s source=%s species=%s confidence=%.2f low=%s",
            result.event_id,
            result.source_event_id,
            result.species,
            result.confidence,
            result.low_confidence,
        )

    def _dead_letter(self, message, reason: str) -> None:
        """Parks the original message, so the partition keeps moving and nothing is lost."""
        self._send(
            self._dead_letter_topic,
            message.value(),
            message.key(),
            headers=[("reason", reason.encode("utf-8")[:1000])],
        )
        log.error("Dead-lettered message to %s: %s", self._dead_letter_topic, reason)

    def _send(self, topic: str, value, key, headers=None) -> None:
        errors = []
        self._producer.produce(
            topic,
            value=value,
            key=key,
            on_delivery=lambda err, _msg: errors.append(err) if err else None,
            headers=headers,
        )
        # Block until Kafka confirms the write, so we never commit an unpublished message.
        if self._producer.flush(self._delivery_timeout) > 0:
            raise KafkaException(f"Timed out delivering a message to {topic}")
        if errors:
            raise KafkaException(errors[0])


def main(settings: Settings | None = None) -> None:
    settings = settings or get_settings()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    source = build_image_source(settings)
    # Load the model before consuming, so the first message doesn't wait for it.
    classifier = get_classifier()

    consumer = Consumer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "group.id": settings.consumer_group,
            # A brand-new group starts from the oldest unprocessed messages, not only new ones.
            "auto.offset.reset": "earliest",
            # We commit ourselves, after the result is published (at-least-once).
            "enable.auto.commit": False,
        }
    )
    producer = Producer({"bootstrap.servers": settings.kafka_bootstrap_servers})
    worker = RecognitionWorker(
        consumer,
        producer,
        handle_fn=lambda event: handle(event, source, classifier, settings.confidence_threshold),
        output_topic=settings.animal_recognized_topic,
        dead_letter_topic=settings.dead_letter_topic,
        max_attempts=settings.max_attempts,
        retry_backoff_seconds=settings.retry_backoff_seconds,
    )

    running = True

    def stop(signum, _frame):
        nonlocal running
        log.info("Received signal %s, shutting down", signum)
        running = False

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    consumer.subscribe([settings.image_received_topic])
    log.info(
        "Recognition worker consuming %s (group=%s, broker=%s, classifier=%s, image source=%s, dlt=%s)",
        settings.image_received_topic,
        settings.consumer_group,
        settings.kafka_bootstrap_servers,
        settings.classifier,
        settings.image_source,
        settings.dead_letter_topic,
    )
    try:
        while running:
            worker.poll_once()
    finally:
        consumer.close()  # leaves the group cleanly so partitions rebalance immediately


if __name__ == "__main__":
    main()
