import json
from uuid import UUID

from app.events import AnimalRecognized, ImageReceived

# Shaped exactly like email-ingestion's Jackson output for ImageReceived.
KOTLIN_IMAGE_RECEIVED = """{
  "storageKey": "inbound/2026/09/15/eba015d1-138e-48fa-a2b2-a228c306def3.png",
  "contentType": "image/png",
  "senderEmail": "hunter@example.com",
  "recipient": "7f3k9qabcdef@in.wildalert.local",
  "hunterId": null,
  "eventId": "283c0964-622c-4e95-a862-1e24a21fafab",
  "occurredAt": "2026-09-15T10:15:30.123456Z"
}"""

# What email-ingestion published before the recipient moved onto the event. Still has to parse:
# an event can sit in a dead-letter topic for days and be replayed after the producer changed.
LEGACY_IMAGE_RECEIVED = """{
  "storageKey": "inbound/2026/09/15/eba015d1-138e-48fa-a2b2-a228c306def3.png",
  "contentType": "image/png",
  "senderEmail": "hunter@example.com",
  "hunterId": "5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60",
  "eventId": "283c0964-622c-4e95-a862-1e24a21fafab",
  "occurredAt": "2026-09-15T10:15:30.123456Z"
}"""


def test_parses_image_received_published_by_kotlin():
    event = ImageReceived.model_validate_json(KOTLIN_IMAGE_RECEIVED)

    assert event.storage_key == "inbound/2026/09/15/eba015d1-138e-48fa-a2b2-a228c306def3.png"
    assert event.recipient == "7f3k9qabcdef@in.wildalert.local"
    assert event.event_id == UUID("283c0964-622c-4e95-a862-1e24a21fafab")


def test_parses_a_legacy_event_that_still_carries_a_hunter_id():
    event = ImageReceived.model_validate_json(LEGACY_IMAGE_RECEIVED)

    assert event.recipient is None
    assert event.hunter_id == UUID("5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60")


def test_image_received_on_no_address_of_ours_has_no_recipient():
    payload = json.loads(KOTLIN_IMAGE_RECEIVED) | {"recipient": None, "senderEmail": None}

    assert ImageReceived.model_validate(payload).recipient is None


def test_unknown_fields_are_ignored_so_producers_can_evolve():
    payload = json.loads(KOTLIN_IMAGE_RECEIVED) | {"cameraId": "north-feeder"}

    assert ImageReceived.model_validate(payload).storage_key.startswith("inbound/")


def test_animal_recognized_is_serialised_with_camel_case_names():
    event = AnimalRecognized(
        source_event_id=UUID("283c0964-622c-4e95-a862-1e24a21fafab"),
        recipient="7f3k9qabcdef@in.wildalert.local",
        storage_key="inbound/x.png",
        species="wild boar",
        confidence=0.97,
        low_confidence=False,
    )

    body = json.loads(event.to_json())

    assert set(body) == {
        "eventId",
        "sourceEventId",
        "recipient",
        "hunterId",
        "storageKey",
        "species",
        "confidence",
        "lowConfidence",
        "occurredAt",
    }
    assert body["sourceEventId"] == "283c0964-622c-4e95-a862-1e24a21fafab"
    assert body["occurredAt"].endswith("Z")  # UTC ISO-8601, readable by Kotlin's Instant
