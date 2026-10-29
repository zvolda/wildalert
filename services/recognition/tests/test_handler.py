from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest

from app.classifier import Recognition
from app.credit import Charge, ChargeOutcome, ChargeRequest
from app.events import ImageReceived
from app.handler import handle
from app.image_source import ImageNotFoundError

IMAGE = b"\x89PNG fake image bytes"
RECIPIENT = "7f3k9qabcdef@wildalert.local"
HUNTER = UUID("bbbbbbbb-0000-4000-8000-000000000002")
COST = 100


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


class RecordingCredit:
    """Stands in for user-account: records what it was asked and answers as configured."""

    def __init__(
        self,
        outcome: ChargeOutcome | None = ChargeOutcome.CHARGED,
        balance_after: int = 2400,
    ) -> None:
        self.outcome = outcome  # None = there is no such hunter
        self.balance_after = balance_after
        self.by_address: list[tuple[str, ChargeRequest]] = []
        self.by_id: list[tuple[UUID, ChargeRequest]] = []

    def charge_by_inbound_address(self, address: str, request: ChargeRequest) -> Charge | None:
        self.by_address.append((address, request))
        return self._answer()

    def charge_by_id(self, hunter_id: UUID, request: ChargeRequest) -> Charge | None:
        self.by_id.append((hunter_id, request))
        return self._answer()

    def _answer(self) -> Charge | None:
        if self.outcome is None:
            return None
        return Charge(
            hunter_id=HUNTER, outcome=self.outcome, balance_after_minor=self.balance_after
        )

    @property
    def calls(self) -> int:
        return len(self.by_address) + len(self.by_id)


def _event(
    key: str = "inbound/2026/09/15/a.png",
    recipient: str | None = RECIPIENT,
    hunter_id: UUID | None = None,
) -> ImageReceived:
    return ImageReceived(
        storage_key=key,
        recipient=recipient,
        hunter_id=hunter_id,
        event_id=uuid4(),
        occurred_at=datetime.now(UTC),
    )


def _handle(event, source, classifier, credit, threshold: float = 0.7):
    return handle(event, source, classifier, credit, threshold=threshold, cost_minor=COST)


def test_classifies_the_stored_image_and_links_back_to_the_source_event():
    event = _event()
    classifier = RecordingClassifier(confidence=0.9)

    result = _handle(event, FakeImageSource({event.storage_key: IMAGE}), classifier, RecordingCredit())

    assert classifier.seen == [IMAGE]  # the fetched bytes are what gets classified
    assert result.species == "red deer"
    assert result.low_confidence is False
    assert result.source_event_id == event.event_id
    # The only thing identifying the hunter: losing it here would lose the alert.
    assert result.recipient == event.recipient
    assert result.storage_key == event.storage_key
    assert result.event_id != event.event_id  # a new event, not a copy


def test_applies_the_confidence_threshold():
    event = _event()

    result = _handle(
        event,
        FakeImageSource({event.storage_key: IMAGE}),
        RecordingClassifier(0.4),
        RecordingCredit(),
    )

    assert result.low_confidence is True


def test_missing_image_is_reported_not_classified_and_not_charged():
    event = _event()
    classifier = RecordingClassifier(confidence=0.9)
    credit = RecordingCredit()

    with pytest.raises(ImageNotFoundError):
        _handle(event, FakeImageSource({}), classifier, credit)

    assert classifier.seen == []
    # There is no refund path, so a photo that cannot be classified must not be charged for.
    assert credit.calls == 0


def test_charges_the_recipient_for_the_photo_before_classifying():
    event = _event()
    credit = RecordingCredit()

    _handle(event, FakeImageSource({event.storage_key: IMAGE}), RecordingClassifier(0.9), credit)

    address, request = credit.by_address[0]
    assert address == RECIPIENT
    assert request.amount_minor == COST
    assert request.entry_type == "RECOGNITION_DEBIT"
    # The id that survives redelivery, and the one notification charges its SMS_DEBIT under.
    assert request.source_event_id == str(event.event_id)
    # Recognition cannot deliver a warning, so it must not claim one — see ChargeRequest.
    assert request.can_deliver_notice is False


def test_an_event_with_only_a_legacy_hunter_id_is_charged_by_id():
    event = _event(recipient=None, hunter_id=HUNTER)
    credit = RecordingCredit()

    result = _handle(
        event, FakeImageSource({event.storage_key: IMAGE}), RecordingClassifier(0.9), credit
    )

    assert credit.by_address == []
    assert credit.by_id[0][0] == HUNTER
    assert result is not None


@pytest.mark.parametrize("outcome", [ChargeOutcome.INSUFFICIENT, ChargeOutcome.INACTIVE])
def test_a_refused_charge_classifies_nothing_and_publishes_nothing(outcome):
    event = _event()
    classifier = RecordingClassifier(confidence=0.9)

    result = _handle(
        event,
        FakeImageSource({event.storage_key: IMAGE}),
        classifier,
        RecordingCredit(outcome=outcome, balance_after=20),
    )

    # No model run — that is the cost this debit exists to protect.
    assert classifier.seen == []
    # No AnimalRecognized, so notification never sees it and nobody is texted. Nothing to undo.
    assert result is None


def test_an_address_that_is_nobodys_is_not_classified():
    """Mail to a made-up name on our domain. Nobody can pay for it, so the model never runs."""
    event = _event()
    classifier = RecordingClassifier(confidence=0.9)

    result = _handle(
        event, FakeImageSource({event.storage_key: IMAGE}), classifier, RecordingCredit(outcome=None)
    )

    assert result is None
    assert classifier.seen == []


def test_an_event_naming_no_hunter_at_all_is_not_charged_or_classified():
    event = _event(recipient=None)
    classifier = RecordingClassifier(confidence=0.9)
    credit = RecordingCredit()

    result = _handle(event, FakeImageSource({event.storage_key: IMAGE}), classifier, credit)

    assert result is None
    assert classifier.seen == []
    assert credit.calls == 0  # there is nobody to ask about


def test_already_charged_is_classified_like_a_fresh_charge():
    """The ordinary redelivery: paid for once, classified again because we store no result."""
    event = _event()
    classifier = RecordingClassifier(confidence=0.9)

    result = _handle(
        event,
        FakeImageSource({event.storage_key: IMAGE}),
        classifier,
        RecordingCredit(outcome=ChargeOutcome.ALREADY_CHARGED),
    )

    assert classifier.seen == [IMAGE]
    assert result is not None
    assert result.source_event_id == event.event_id
