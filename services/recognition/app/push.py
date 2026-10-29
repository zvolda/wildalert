"""Pub/Sub push endpoint: receives ImageReceived, recognizes the animal, publishes the result.

A thin transport adapter — the work is `app.handler.handle`. This replaced a Kafka worker
(`python -m app.worker`), so the service is now a single FastAPI app with no second process.

**The HTTP status is the acknowledgement**, and Pub/Sub's subscription settings supply the retry
and dead-letter policy the worker used to implement by hand:

- 204 — handled and the result published. Pub/Sub acks. **Also the answer when nobody paid for
  the photo** (no hunter at that address, no credit, hunter switched off): nothing is classified
  and nothing is published, and retrying would reach the same refusal. See `app.handler`.
- 400 — the message is not a valid ImageReceived and never will be. Pub/Sub retries, then parks it
  in the subscription's dead-letter topic, which is the only place its original bytes survive.
- 404 — no image is stored under the event's key. Also dead-lettered eventually, but retried first,
  which is an improvement: a briefly-inconsistent storage write now gets another chance.
- 500 — anything else (storage error, model failure, publish failure). Pub/Sub redelivers with
  backoff and dead-letters after the configured number of attempts.

**Delivery is at-least-once**, and a redelivery re-runs classification: the same ImageReceived
produces a *new* AnimalRecognized.event_id with the *same* source_event_id. That is exactly why
notification keys its idempotency on source_event_id — without it, a hunter would get two texts.
"""

import base64
import binascii
import logging

from fastapi import APIRouter, Depends, HTTPException, Response, status
from pydantic import BaseModel, ConfigDict, ValidationError
from pydantic.alias_generators import to_camel

from app.classifier import Classifier, get_classifier
from app.config import Settings, get_settings
from app.credit import CreditClient, get_credit_client
from app.events import ImageReceived
from app.handler import handle
from app.image_source import ImageNotFoundError, ImageSource, get_image_source
from app.publisher import EventPublisher, get_event_publisher

log = logging.getLogger("recognition.push")

router = APIRouter()


class PubSubPushMessage(BaseModel):
    """The message inside a push request. `data` is the base64-encoded event JSON."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    data: str | None = None
    message_id: str | None = None
    publish_time: str | None = None
    attributes: dict[str, str] | None = None
    ordering_key: str | None = None
    delivery_attempt: int | None = None


class PubSubPushEnvelope(BaseModel):
    """What Pub/Sub POSTs to a push endpoint.

    Everything is optional: this is someone else's wire format, and a surprise should become a
    logged ack/nack decision rather than a 422 before our code runs. `deliveryAttempt` is accepted
    at either level because it has been observed in both; nothing depends on it.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    message: PubSubPushMessage | None = None
    subscription: str | None = None
    delivery_attempt: int | None = None

    def attempt(self) -> int | None:
        if self.delivery_attempt is not None:
            return self.delivery_attempt
        return self.message.delivery_attempt if self.message else None


@router.post("/events/image-received", status_code=status.HTTP_204_NO_CONTENT)
def on_image_received(
    envelope: PubSubPushEnvelope,
    source: ImageSource = Depends(get_image_source),
    classifier: Classifier = Depends(get_classifier),
    credit: CreditClient = Depends(get_credit_client),
    publisher: EventPublisher = Depends(get_event_publisher),
    settings: Settings = Depends(get_settings),
) -> Response:
    data = envelope.message.data if envelope.message else None
    if not data:
        # Nothing to preserve, so acking beats filling the dead-letter topic.
        log.warning("Push with no message data (subscription=%s); acking", envelope.subscription)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    message_id = envelope.message.message_id if envelope.message else None

    try:
        payload = base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError) as e:
        log.warning("Push message data is not valid base64; dead-lettering: %s", e)
        raise HTTPException(status_code=400, detail="message.data is not valid base64") from e

    try:
        event = ImageReceived.model_validate_json(payload)
    except ValidationError as e:
        log.warning(
            "Invalid ImageReceived (messageId=%s); dead-lettering: %s", message_id, e
        )
        raise HTTPException(status_code=400, detail="not a valid ImageReceived") from e

    log.info(
        "Received ImageReceived id=%s key=%s (messageId=%s attempt=%s)",
        event.event_id, event.storage_key, message_id, envelope.attempt(),
    )

    try:
        result = handle(
            event,
            source,
            classifier,
            credit,
            threshold=settings.confidence_threshold,
            cost_minor=settings.recognition_cost_minor,
        )
    except ImageNotFoundError as e:
        log.warning("Nothing to recognize for ImageReceived id=%s: %s", event.event_id, e)
        raise HTTPException(status_code=404, detail=str(e)) from e

    if result is None:
        # Nobody paid for this photo, so there is nothing to publish and nothing to retry: a
        # redelivery would reach the same refusal. Acking is the honest answer — the message is
        # not poison, and filling the dead-letter topic with photos from unpaid accounts would
        # bury the ones that really failed. handle() has logged which refusal it was.
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    # Raises on failure, so Pub/Sub redelivers rather than losing the result.
    publisher.publish(result)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
