"""Kafka worker: consumes `image.received`, runs recognition, publishes `animal.recognized`.

Run with `python -m app.worker` (same image as the HTTP API, different command). This is a thin
transport adapter — the actual work is `app.handler.handle`.

Delivery is **at-least-once**: a message's offset is committed only after its result has been
delivered to Kafka. If the worker dies in between, the message is redelivered and handled again —
so consumers of `animal.recognized` must tolerate duplicates (that is what `sourceEventId` is for).

Failure policy (retries and a dead-letter topic come in the hardening slice):
- A message that can never succeed (invalid JSON / schema, or no image under its key) is logged
  and skipped, so one bad message can't block the partition forever.
- Anything else (broker, storage or model failure) is raised without committing: the worker exits
  and, once restarted, retries from the last committed offset.
"""

import logging
import signal
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

    def produce(self, topic: str, value: str, key: str, on_delivery) -> None: ...

    def flush(self, timeout: float) -> int: ...


def parse_and_handle(value: bytes | None, handle_fn: HandleFn) -> AnimalRecognized | None:
    """Turns one raw message into a result, or None if the message should be skipped."""
    try:
        event = ImageReceived.model_validate_json(value or b"")
    except ValidationError as e:
        log.warning("Skipping invalid ImageReceived message: %s", e)
        return None
    try:
        return handle_fn(event)
    except ImageNotFoundError as e:
        log.warning("Skipping ImageReceived id=%s: %s", event.event_id, e)
        return None


class RecognitionWorker:
    def __init__(
        self,
        consumer: MessageConsumer,
        producer: MessageProducer,
        handle_fn: HandleFn,
        output_topic: str,
        delivery_timeout: float = 30.0,
    ) -> None:
        self._consumer = consumer
        self._producer = producer
        self._handle_fn = handle_fn
        self._output_topic = output_topic
        self._delivery_timeout = delivery_timeout

    def poll_once(self, timeout: float = 1.0) -> None:
        """Processes at most one message: handle → publish → wait for delivery → commit."""
        message = self._consumer.poll(timeout)
        if message is None:
            return
        if message.error():
            # Transient client/broker conditions; librdkafka keeps retrying underneath.
            log.warning("Kafka consumer error: %s", message.error())
            return

        result = parse_and_handle(message.value(), self._handle_fn)
        if result is not None:
            self._publish(result)
        self._consumer.commit(message=message, asynchronous=False)

    def _publish(self, result: AnimalRecognized) -> None:
        errors = []
        # Keyed like email-ingestion: by hunter, so one hunter's events stay ordered.
        key = str(result.hunter_id or result.event_id)
        self._producer.produce(
            self._output_topic,
            value=result.to_json(),
            key=key,
            on_delivery=lambda err, _msg: errors.append(err) if err else None,
        )
        # Block until Kafka confirms the write, so we never commit an unpublished result.
        if self._producer.flush(self._delivery_timeout) > 0:
            raise KafkaException(f"Timed out delivering AnimalRecognized id={result.event_id}")
        if errors:
            raise KafkaException(errors[0])
        log.info(
            "Published AnimalRecognized id=%s source=%s species=%s confidence=%.2f low=%s",
            result.event_id,
            result.source_event_id,
            result.species,
            result.confidence,
            result.low_confidence,
        )


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
        "Recognition worker consuming %s (group=%s, broker=%s, classifier=%s, image source=%s)",
        settings.image_received_topic,
        settings.consumer_group,
        settings.kafka_bootstrap_servers,
        settings.classifier,
        settings.image_source,
    )
    try:
        while running:
            worker.poll_once()
    finally:
        consumer.close()  # leaves the group cleanly so partitions rebalance immediately


if __name__ == "__main__":
    main()
