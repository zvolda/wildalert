import json
from uuid import UUID

from app.events import AnimalRecognized, ImageReceived

# Shaped exactly like email-ingestion's Jackson output for ImageReceived.
KOTLIN_IMAGE_RECEIVED = """{
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
    assert event.hunter_id == UUID("5b0f2f2e-8c1d-4b7a-9e3f-1d2c3b4a5f60")
    assert event.event_id == UUID("283c0964-622c-4e95-a862-1e24a21fafab")


def test_image_received_from_unknown_sender_has_no_hunter():
    payload = json.loads(KOTLIN_IMAGE_RECEIVED) | {"hunterId": None, "senderEmail": None}

    assert ImageReceived.model_validate(payload).hunter_id is None


def test_unknown_fields_are_ignored_so_producers_can_evolve():
    payload = json.loads(KOTLIN_IMAGE_RECEIVED) | {"cameraId": "north-feeder"}

    assert ImageReceived.model_validate(payload).storage_key.startswith("inbound/")


def test_animal_recognized_is_serialised_with_camel_case_names():
    event = AnimalRecognized(
        source_event_id=UUID("283c0964-622c-4e95-a862-1e24a21fafab"),
        hunter_id=None,
        storage_key="inbound/x.png",
        species="wild boar",
        confidence=0.97,
        low_confidence=False,
    )

    body = json.loads(event.to_json())

    assert set(body) == {
        "eventId",
        "sourceEventId",
        "hunterId",
        "storageKey",
        "species",
        "confidence",
        "lowConfidence",
        "occurredAt",
    }
    assert body["sourceEventId"] == "283c0964-622c-4e95-a862-1e24a21fafab"
    assert body["occurredAt"].endswith("Z")  # UTC ISO-8601, readable by Kotlin's Instant
