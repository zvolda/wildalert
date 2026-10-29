"""The recognition use case, independent of how events arrive.

`handle` knows nothing about the transport: the Pub/Sub push endpoint calls it for each delivered
message, and it was called by a Kafka worker before that without this code changing.
"""

import logging

from app.classifier import Classifier, apply_threshold
from app.credit import Charge, ChargeRequest, CreditClient
from app.events import AnimalRecognized, ImageReceived
from app.image_source import ImageSource

log = logging.getLogger("recognition.handler")


def handle(
    event: ImageReceived,
    source: ImageSource,
    classifier: Classifier,
    credit: CreditClient,
    threshold: float,
    cost_minor: int,
) -> AnimalRecognized | None:
    """Fetches the stored image, charges the hunter, classifies it and applies the threshold.

    Returns None when nobody paid for the photo — an address that is not a hunter's, an empty
    balance, a switched-off hunter. Nothing is classified and nothing is published in that case, so
    no `AnimalRecognized` exists, notification never sees it and no SMS goes out. **There is
    therefore nothing to compensate**, which is the whole reason the charge comes before the model
    and not after: charging afterwards would let a drained account buy a free DeepFaune run, which
    is the cost this debit exists to protect.

    Raises ImageNotFoundError when nothing is stored under the event's key — before any money
    moves, deliberately. A missing image is retried and eventually dead-lettered, and there is no
    refund path in this design, so a photo that cannot be classified must not be charged for.

    The order is: fetch (cheap, and proves there is something to classify) → charge → classify
    (seconds of CPU, the thing being paid for).
    """
    image_bytes = source.get(event.storage_key)

    charge = _charge_for(event, credit, cost_minor)
    if charge is None:
        log.info("Not classifying image %s: no hunter to charge", event.event_id)
        return None
    if not charge.payable:
        # A refusal, not a failure: user-account wrote nothing, so there is nothing to undo and
        # nothing to retry — a redelivery would only produce the same refusal.
        log.info(
            "Not classifying image %s: hunter %s was not charged (%s), balance %s haléře",
            event.event_id, charge.hunter_id, charge.outcome, charge.balance_after_minor,
        )
        return None

    result = apply_threshold(classifier.classify(image_bytes), threshold)
    log.info(
        "Charged hunter %s %s haléře for image %s (%s), balance %s haléře",
        charge.hunter_id, cost_minor, event.event_id, charge.outcome, charge.balance_after_minor,
    )
    return AnimalRecognized(
        source_event_id=event.event_id,
        # Passed straight through: recognition has no interest in who the alert is for, it just
        # must not lose the only thing that identifies them.
        recipient=event.recipient,
        hunter_id=event.hunter_id,
        storage_key=event.storage_key,
        species=result.species,
        confidence=result.confidence,
        low_confidence=result.low_confidence,
    )


def _charge_for(event: ImageReceived, credit: CreditClient, cost_minor: int) -> Charge | None:
    """Resolves whose photo this is and charges them for it, in one call.

    The recipient address is the live path — the only identity the pipeline carries since the
    hunter lookup moved off the webhook. `hunter_id` is the fallback for an event published before
    that change, including one replayed from a dead-letter topic days later.
    """
    # Keyed on the ImageReceived event id, which is what becomes AnimalRecognized's sourceEventId:
    # it survives redelivery, so one photo is charged once however many times it is delivered. It
    # is also the key notification charges its SMS_DEBIT under, which is exactly why user-account's
    # unique index covers (entry_type, external_ref) and not the ref alone.
    request = ChargeRequest(source_event_id=str(event.event_id), amount_minor=cost_minor)

    if event.recipient:
        charge = credit.charge_by_inbound_address(event.recipient, request)
        if charge is None:
            log.info("Image %s: %s is not a hunter's address", event.event_id, event.recipient)
        return charge
    if event.hunter_id:
        log.info(
            "Image %s carries no recipient; falling back to hunter id %s",
            event.event_id, event.hunter_id,
        )
        return credit.charge_by_id(event.hunter_id, request)
    # Mail to our domain that named no hunter at all. Before this slice such a photo was still
    # classified and published, and notification then declined to text anyone — so it cost a
    # DeepFaune run that no balance paid for. Now it costs a storage read.
    log.info("Image %s names no hunter at all", event.event_id)
    return None
