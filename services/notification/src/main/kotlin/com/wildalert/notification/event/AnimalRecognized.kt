package com.wildalert.notification.event

import java.time.Instant
import java.util.UUID

/**
 * Consumed from `animal.recognized` — published by the Python recognition worker once per
 * classified image. Mirrors `AnimalRecognized` in services/recognition/app/events.py.
 *
 * sourceEventId is the ImageReceived it came from; delivery is at-least-once, so the same
 * sourceEventId can arrive twice — `ProcessedEvents` dedupes on it.
 *
 * [recipient] is the hunter's inbound address, carried from email-ingestion through recognition
 * untouched. **This service resolves it to a hunter**; email-ingestion used to, inside its
 * webhook, and a scaled-to-zero user-account then timed out and the alert vanished with a 200.
 * Here a failed lookup throws, the push answers 5xx and Pub/Sub redelivers — so a cold start
 * delays the alert instead of losing it.
 *
 * [hunterId] is deprecated and null on new events; still read as a fallback so an event
 * published before the change (or replayed from a dead-letter topic) still reaches its hunter.
 */
data class AnimalRecognized(
    val eventId: UUID,
    val sourceEventId: UUID,
    val recipient: String?,
    val hunterId: UUID? = null,
    val storageKey: String,
    val species: String,
    val confidence: Double,
    val lowConfidence: Boolean,
    val occurredAt: Instant,
)
