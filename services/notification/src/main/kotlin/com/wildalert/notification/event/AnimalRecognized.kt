package com.wildalert.notification.event

import java.time.Instant
import java.util.UUID

/**
 * Consumed from `animal.recognized` — published by the Python recognition worker once per
 * classified image. Mirrors `AnimalRecognized` in services/recognition/app/events.py.
 *
 * sourceEventId is the ImageReceived it came from; delivery is at-least-once, so the same
 * sourceEventId can arrive twice (deduplication comes in the hardening slice).
 */
data class AnimalRecognized(
    val eventId: UUID,
    val sourceEventId: UUID,
    val hunterId: UUID?,
    val storageKey: String,
    val species: String,
    val confidence: Double,
    val lowConfidence: Boolean,
    val occurredAt: Instant,
)
