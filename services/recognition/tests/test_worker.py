import json

import pytest
from confluent_kafka import KafkaException

from app.events import AnimalRecognized, ImageReceived
from app.image_source import ImageNotFoundError
from app.worker import RecognitionWorker

VALID = json.dumps(
    {
        "storageKey": "inbound/2026/09/15/a.png",
        "hunterId": "5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60",
        "eventId": "283c0964-622c-4e95-a862-1e24a21fafab",
        "occurredAt": "2026-09-15T10:15:30Z",
    }
).encode()

OUT = "animal.recognized"
DLT = "image.received.dlt"


# --- Fakes standing in for confluent_kafka's Consumer / Producer / Message ------------------


class FakeMessage:
    def __init__(self, value: bytes | None, key: bytes | None = b"k", error=None) -> None:
        self._value = value
        self._key = key
        self._error = error

    def value(self):
        return self._value

    def key(self):
        return self._key

    def error(self):
        return self._error


class FakeConsumer:
    def __init__(self, *messages: FakeMessage) -> None:
        self.messages = list(messages)
        self.committed: list[FakeMessage] = []

    def poll(self, timeout: float):
        return self.messages.pop(0) if self.messages else None

    def commit(self, message, asynchronous: bool) -> None:
        self.committed.append(message)


class FakeProducer:
    def __init__(self, delivery_error=None, undelivered: int = 0) -> None:
        self.delivery_error = delivery_error
        self.undelivered = undelivered
        self.produced: list[dict] = []
        self._pending = []

    def produce(self, topic: str, value, key, on_delivery, headers=None) -> None:
        self.produced.append({"topic": topic, "key": key, "value": value, "headers": headers})
        self._pending.append(on_delivery)

    def flush(self, timeout: float) -> int:
        for callback in self._pending:
            callback(self.delivery_error, None)
        self._pending.clear()
        return self.undelivered

    def on(self, topic: str) -> list[dict]:
        return [p for p in self.produced if p["topic"] == topic]


def recognise(event: ImageReceived) -> AnimalRecognized:
    return AnimalRecognized(
        source_event_id=event.event_id,
        hunter_id=event.hunter_id,
        storage_key=event.storage_key,
        species="wild boar",
        confidence=0.97,
        low_confidence=False,
    )


def _worker(consumer, producer, handle_fn=recognise, **kwargs) -> RecognitionWorker:
    slept: list[float] = []
    worker = RecognitionWorker(
        consumer,
        producer,
        handle_fn,
        output_topic=OUT,
        dead_letter_topic=DLT,
        sleep=slept.append,
        **kwargs,
    )
    worker.slept = slept  # test-only handle on the retry pauses
    return worker


def reason_of(dead_lettered: dict) -> str:
    return dict(dead_lettered["headers"])["reason"].decode()


# --- Happy path -----------------------------------------------------------------------------


def test_publishes_result_keyed_by_hunter_then_commits():
    message = FakeMessage(VALID)
    consumer, producer = FakeConsumer(message), FakeProducer()

    _worker(consumer, producer).poll_once()

    [published] = producer.on(OUT)
    assert published["key"] == "5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60"
    body = json.loads(published["value"])
    assert body["species"] == "wild boar"
    assert body["sourceEventId"] == "283c0964-622c-4e95-a862-1e24a21fafab"
    assert producer.on(DLT) == []
    assert consumer.committed == [message]


def test_result_for_unknown_sender_is_keyed_by_its_event_id():
    unknown = json.loads(VALID) | {"hunterId": None}
    consumer, producer = FakeConsumer(FakeMessage(json.dumps(unknown).encode())), FakeProducer()

    _worker(consumer, producer).poll_once()

    [published] = producer.on(OUT)
    assert published["key"] == json.loads(published["value"])["eventId"]


# --- Dead-lettering -------------------------------------------------------------------------


def test_invalid_message_is_dead_lettered_with_its_original_bytes_then_committed():
    message = FakeMessage(b'{"not": "an ImageReceived"}')
    consumer, producer = FakeConsumer(message), FakeProducer()

    _worker(consumer, producer).poll_once()

    [dead] = producer.on(DLT)
    assert dead["value"] == b'{"not": "an ImageReceived"}'  # replayable as-is
    assert dead["key"] == b"k"
    assert "invalid ImageReceived" in reason_of(dead)
    assert producer.on(OUT) == []
    assert consumer.committed == [message]


def test_missing_image_is_dead_lettered_without_retrying():
    def image_gone(event):
        raise ImageNotFoundError(event.storage_key)

    consumer, producer = FakeConsumer(FakeMessage(VALID)), FakeProducer()
    worker = _worker(consumer, producer, handle_fn=image_gone)

    worker.poll_once()

    assert len(producer.on(DLT)) == 1
    assert "No image stored under key" in reason_of(producer.on(DLT)[0])
    assert worker.slept == []  # retrying could never help


def test_a_message_that_keeps_failing_is_dead_lettered_after_the_configured_attempts():
    attempts = []

    def always_fails(event):
        attempts.append(event.event_id)
        raise RuntimeError("model exploded")

    message = FakeMessage(VALID)
    consumer, producer = FakeConsumer(message), FakeProducer()
    worker = _worker(consumer, producer, handle_fn=always_fails, max_attempts=3, retry_backoff_seconds=2.0)

    worker.poll_once()

    assert len(attempts) == 3
    assert worker.slept == [2.0, 4.0]  # growing pause, none after the last attempt
    assert "failed after 3 attempts" in reason_of(producer.on(DLT)[0])
    assert producer.on(OUT) == []
    assert consumer.committed == [message]


def test_a_transient_failure_is_retried_and_then_succeeds():
    calls = []

    def fails_once(event):
        calls.append(event.event_id)
        if len(calls) == 1:
            raise ConnectionError("R2 hiccup")
        return recognise(event)

    message = FakeMessage(VALID)
    consumer, producer = FakeConsumer(message), FakeProducer()
    worker = _worker(consumer, producer, handle_fn=fails_once)

    worker.poll_once()

    assert len(calls) == 2
    assert len(producer.on(OUT)) == 1
    assert producer.on(DLT) == []
    assert consumer.committed == [message]


# --- Kafka itself failing: never dead-letter, never commit -----------------------------------


def test_failed_delivery_is_raised_without_committing():
    consumer = FakeConsumer(FakeMessage(VALID))

    with pytest.raises(KafkaException):
        _worker(consumer, FakeProducer(delivery_error="broker down")).poll_once()

    assert consumer.committed == []


def test_delivery_timeout_is_raised_without_committing():
    consumer = FakeConsumer(FakeMessage(VALID))

    with pytest.raises(KafkaException):
        _worker(consumer, FakeProducer(undelivered=1)).poll_once()

    assert consumer.committed == []


def test_idle_poll_and_consumer_errors_do_nothing():
    consumer, producer = FakeConsumer(FakeMessage(None, error="transport failure")), FakeProducer()
    worker = _worker(consumer, producer)

    worker.poll_once()  # error message
    worker.poll_once()  # nothing to read

    assert producer.produced == []
    assert consumer.committed == []
