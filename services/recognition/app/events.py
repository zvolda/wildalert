"""Event contracts shared with the Kotlin services, as broker-agnostic JSON.

Field names are camelCase on the wire (what Jackson produces in Kotlin) and snake_case in Python;
pydantic maps between them. Unknown fields are ignored, so producers can add fields without
breaking this consumer.
"""

from datetime import UTC, datetime
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class _Event(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    def to_json(self) -> str:
        return self.model_dump_json(by_alias=True)


class ImageReceived(_Event):
    """Consumed from `image.received` — published by email-ingestion once per stored image.

    `recipient` is the hunter's inbound address the email was sent to — who the alert is for.
    It is deliberately not resolved to a hunter id upstream: doing that meant a synchronous call
    to user-account inside the webhook, and a scaled-to-zero user-account silently cost the alert.
    We only carry it through to AnimalRecognized; notification resolves it.

    `hunter_id` is deprecated and always absent on new events. Kept so events published before
    the change (including any sitting in a dead-letter topic) still parse.
    """

    storage_key: str
    content_type: str | None = None
    sender_email: str | None = None
    recipient: str | None = None
    hunter_id: UUID | None = None
    event_id: UUID
    occurred_at: datetime


class AnimalRecognized(_Event):
    """Published to `animal.recognized` — one per ImageReceived that was classified.

    source_event_id points back at the ImageReceived it came from, so a consumer can recognise a
    redelivered result and not text the hunter twice.
    """

    event_id: UUID = Field(default_factory=uuid4)
    source_event_id: UUID
    recipient: str | None = None
    hunter_id: UUID | None = None
    storage_key: str
    species: str
    confidence: float = Field(ge=0.0, le=1.0)
    low_confidence: bool
    occurred_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
