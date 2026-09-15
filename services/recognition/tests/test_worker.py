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


# --- Fakes standing in for confluent_kafka's Consumer / Producer / Message ------------------


class FakeMessage:
    def __init__(self, value: bytes | None, error=None) -> None:
        self._value = value
        self._error = error

    def value(self):
        return self._value

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
        self.produced: list[tuple[str, str, str]] = []
        self._pending = []

    def produce(self, topic: str, value: str, key: str, on_delivery) -> None:
        self.produced.append((topic, key, value))
        self._pending.append(on_delivery)

    def flush(self, timeout: float) -> int:
        for callback in self._pending:
            callback(self.delivery_error, None)
        self._pending.clear()
        return self.undelivered


def recognise(event: ImageReceived) -> AnimalRecognized:
    return AnimalRecognized(
        source_event_id=event.event_id,
        hunter_id=event.hunter_id,
        storage_key=event.storage_key,
        species="wild boar",
        confidence=0.97,
        low_confidence=False,
    )


def _worker(consumer, producer, handle_fn=recognise) -> RecognitionWorker:
    return RecognitionWorker(consumer, producer, handle_fn, output_topic="animal.recognized")


# --- Tests ----------------------------------------------------------------------------------


def test_publishes_result_keyed_by_hunter_then_commits():
    message = FakeMessage(VALID)
    consumer, producer = FakeConsumer(message), FakeProducer()

    _worker(consumer, producer).poll_once()

    [(topic, key, value)] = producer.produced
    assert topic == "animal.recognized"
    assert key == "5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60"
    body = json.loads(value)
    assert body["species"] == "wild boar"
    assert body["sourceEventId"] == "283c0964-622c-4e95-a862-1e24a21fafab"
    assert consumer.committed == [message]


def test_invalid_message_is_skipped_and_committed():
    message = FakeMessage(b'{"not": "an ImageReceived"}')
    consumer, producer = FakeConsumer(message), FakeProducer()

    _worker(consumer, producer).poll_once()

    assert producer.produced == []
    assert consumer.committed == [message]


def test_missing_image_is_skipped_and_committed():
    def image_gone(event):
        raise ImageNotFoundError(event.storage_key)

    message = FakeMessage(VALID)
    consumer, producer = FakeConsumer(message), FakeProducer()

    _worker(consumer, producer, handle_fn=image_gone).poll_once()

    assert producer.produced == []
    assert consumer.committed == [message]


def test_unexpected_failure_is_raised_without_committing_so_the_message_is_retried():
    def storage_down(event):
        raise ConnectionError("R2 unreachable")

    consumer = FakeConsumer(FakeMessage(VALID))

    with pytest.raises(ConnectionError):
        _worker(consumer, FakeProducer(), handle_fn=storage_down).poll_once()

    assert consumer.committed == []


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


def test_result_for_unknown_sender_is_keyed_by_its_event_id():
    unknown = json.loads(VALID) | {"hunterId": None}
    consumer, producer = FakeConsumer(FakeMessage(json.dumps(unknown).encode())), FakeProducer()

    _worker(consumer, producer).poll_once()

    [(_, key, value)] = producer.produced
    assert key == json.loads(value)["eventId"]
