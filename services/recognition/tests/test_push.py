"""Tests for the Pub/Sub push endpoint.

Ported from the Kafka worker's tests. The retry/backoff assertions are gone on purpose: the number
of attempts and the pause between them are now subscription settings, not code, so they are proven
by the local emulator check in the plan rather than here. What remains is the part that *is* code —
which status each outcome produces, because the status is what Pub/Sub acts on:

    204 ack · 400 unparseable · 404 image missing · 500 everything else
"""

import base64
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

from app.classifier import Recognition
from app.credit import Charge, ChargeOutcome, ChargeRequest, get_credit_client
from app.events import AnimalRecognized
from app.image_source import ImageNotFoundError, get_image_source
from app.main import app, get_classifier
from app.publisher import get_event_publisher

client = TestClient(app)

RECIPIENT = "7f3k9qabcdef@wildalert.local"
SOURCE_EVENT_ID = UUID("aaaaaaaa-0000-4000-8000-000000000001")
STORAGE_KEY = "inbound/2026/09/15/11111111-1111-4111-8111-111111111111.jpg"


def image_received(
    recipient: str | None = RECIPIENT, event_id: UUID = SOURCE_EVENT_ID
) -> str:
    """The JSON email-ingestion publishes (camelCase, as Jackson produces it)."""
    to = f'"{recipient}"' if recipient else "null"
    return (
        f'{{"storageKey":"{STORAGE_KEY}","contentType":"image/jpeg",'
        f'"senderEmail":"hunter@example.com","recipient":{to},"hunterId":null,'
        f'"eventId":"{event_id}","occurredAt":"2026-09-15T11:40:58.981643Z"}}'
    )


def envelope(payload: str | bytes, message_id: str = "1") -> dict:
    data = payload.encode() if isinstance(payload, str) else payload
    return {
        "message": {"data": base64.b64encode(data).decode(), "messageId": message_id},
        "subscription": "projects/wildalert-local/subscriptions/image.received.recognition",
    }


class FakeImageSource:
    def __init__(self, missing: bool = False) -> None:
        self.missing = missing
        self.requested: list[str] = []

    def get(self, key: str) -> bytes:
        self.requested.append(key)
        if self.missing:
            raise ImageNotFoundError(key)
        return b"\x89PNG\r\n\x1a\n fake image bytes"


class FakePublisher:
    def __init__(self, fail_with: Exception | None = None) -> None:
        self.published: list[AnimalRecognized] = []
        self.fail_with = fail_with

    def publish(self, event: AnimalRecognized) -> None:
        if self.fail_with:
            raise self.fail_with
        self.published.append(event)


class FailingClassifier:
    def classify(self, image_bytes: bytes) -> Recognition:
        raise RuntimeError("model exploded")


class FakeCredit:
    """Stands in for user-account. Charging is on in every environment, so there is no fake default
    in the service itself — the test supplies this one through the dependency override."""

    def __init__(
        self,
        outcome: ChargeOutcome | None = ChargeOutcome.CHARGED,
        fail_with: Exception | None = None,
    ) -> None:
        self.outcome = outcome
        self.fail_with = fail_with
        self.charged: list[ChargeRequest] = []

    def charge_by_inbound_address(self, address: str, request: ChargeRequest) -> Charge | None:
        return self._answer(request)

    def charge_by_id(self, hunter_id, request: ChargeRequest) -> Charge | None:
        return self._answer(request)

    def _answer(self, request: ChargeRequest) -> Charge | None:
        if self.fail_with:
            raise self.fail_with
        self.charged.append(request)
        if self.outcome is None:
            return None
        return Charge(
            hunter_id=UUID("bbbbbbbb-0000-4000-8000-000000000002"),
            outcome=self.outcome,
            balance_after_minor=2400,
        )


@pytest.fixture
def source() -> FakeImageSource:
    return FakeImageSource()


@pytest.fixture
def publisher() -> FakePublisher:
    return FakePublisher()


@pytest.fixture
def credit() -> FakeCredit:
    return FakeCredit()


@pytest.fixture(autouse=True)
def wire(source: FakeImageSource, publisher: FakePublisher, credit: FakeCredit):
    app.dependency_overrides[get_image_source] = lambda: source
    app.dependency_overrides[get_event_publisher] = lambda: publisher
    app.dependency_overrides[get_credit_client] = lambda: credit
    yield
    app.dependency_overrides.clear()


def test_recognizes_the_stored_image_and_publishes_the_result(source, publisher):
    response = client.post("/events/image-received", json=envelope(image_received()))

    assert response.status_code == 204
    assert source.requested == [STORAGE_KEY]
    result = publisher.published[0]
    assert result.source_event_id == SOURCE_EVENT_ID
    assert result.recipient == RECIPIENT
    assert result.storage_key == STORAGE_KEY
    assert result.species == "wild boar"  # the default stub classifier
    assert result.low_confidence is False


def test_an_event_naming_no_hunter_is_acknowledged_and_not_classified(publisher, source):
    """It used to be classified and published, with notification declining to text anyone.

    Since the recognition debit there is nobody to charge for it, so it is acked and dropped: mail
    to a made-up name on our domain costs a storage read and no model run.
    """
    response = client.post(
        "/events/image-received", json=envelope(image_received(recipient=None))
    )

    assert response.status_code == 204
    assert publisher.published == []


def test_invalid_event_is_dead_lettered_without_publishing(publisher):
    not_json = client.post("/events/image-received", json=envelope("not json at all"))
    partial = client.post("/events/image-received", json=envelope('{"storageKey":"x.jpg"}'))

    assert not_json.status_code == 400
    assert partial.status_code == 400
    assert publisher.published == []


def test_data_that_is_not_base64_is_dead_lettered(publisher):
    response = client.post(
        "/events/image-received",
        json={"message": {"data": "!!! not base64 !!!"}, "subscription": "s"},
    )

    assert response.status_code == 400
    assert publisher.published == []


def test_the_photo_is_charged_for_before_it_is_classified(publisher, credit):
    client.post("/events/image-received", json=envelope(image_received()))

    assert credit.charged[0].source_event_id == str(SOURCE_EVENT_ID)
    assert credit.charged[0].amount_minor == 100  # the default price
    assert credit.charged[0].entry_type == "RECOGNITION_DEBIT"
    assert len(publisher.published) == 1


def test_an_unpaid_photo_is_acknowledged_without_publishing(publisher, credit):
    """A refusal is not a failure: nothing was written, so a redelivery would only repeat it.

    Acking also keeps the dead-letter topic for messages that really failed, rather than filling
    it with photos from empty accounts.
    """
    credit.outcome = ChargeOutcome.INSUFFICIENT

    response = client.post("/events/image-received", json=envelope(image_received()))

    assert response.status_code == 204
    assert publisher.published == []


def test_a_charge_that_cannot_be_made_is_not_acknowledged(publisher, credit):
    """A cold or unreachable user-account must cost a redelivery, never the photo."""
    credit.fail_with = RuntimeError("user-account is cold")

    with pytest.raises(RuntimeError, match="user-account is cold"):
        client.post("/events/image-received", json=envelope(image_received()))

    assert publisher.published == []


def test_missing_image_is_reported_as_not_found(publisher, source, credit):
    source.missing = True

    response = client.post("/events/image-received", json=envelope(image_received()))

    assert response.status_code == 404
    assert publisher.published == []
    # No refund path exists, so a photo that cannot be classified is not charged for.
    assert credit.charged == []


def test_a_failing_classifier_returns_500_so_pubsub_redelivers(publisher):
    app.dependency_overrides[get_classifier] = lambda: FailingClassifier()
    try:
        with pytest.raises(RuntimeError, match="model exploded"):
            client.post("/events/image-received", json=envelope(image_received()))
    finally:
        app.dependency_overrides.pop(get_classifier)

    assert publisher.published == []


def test_a_failed_publish_is_not_acknowledged():
    app.dependency_overrides[get_event_publisher] = lambda: FakePublisher(
        fail_with=RuntimeError("pubsub is down")
    )

    with pytest.raises(RuntimeError, match="pubsub is down"):
        client.post("/events/image-received", json=envelope(image_received()))


def test_an_envelope_without_message_data_is_acknowledged(publisher):
    empty = client.post("/events/image-received", json={"message": {"data": ""}})
    missing = client.post("/events/image-received", json={"subscription": "s"})

    assert empty.status_code == 204
    assert missing.status_code == 204
    assert publisher.published == []


def test_unknown_envelope_fields_and_delivery_attempt_are_tolerated(publisher):
    body = envelope(image_received())
    body["deliveryAttempt"] = 3
    body["somethingNew"] = "ignored"
    body["message"]["publishTime"] = "2026-09-15T11:40:59Z"
    body["message"]["attributes"] = {"origin": "test"}

    response = client.post("/events/image-received", json=body)

    assert response.status_code == 204
    assert len(publisher.published) == 1


def test_at_least_once_the_same_event_twice_is_published_twice(publisher):
    """Pub/Sub can redeliver, and we re-run recognition: a new eventId, the same sourceEventId.

    Deduplication is notification's job (keyed on sourceEventId) — not this endpoint's.
    """
    body = envelope(image_received(event_id=uuid4()))

    client.post("/events/image-received", json=body)
    client.post("/events/image-received", json=body)

    assert len(publisher.published) == 2
    first, second = publisher.published
    assert first.source_event_id == second.source_event_id
    assert first.event_id != second.event_id
