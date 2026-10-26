package com.wildalert.detectionhistory.event

import java.time.Instant
import java.util.UUID

/**
 * Consumed from `animal.recognized` — published by the Python recognition worker once per
 * classified image. Mirrors `AnimalRecognized` in services/recognition/app/events.py, and is a
 * deliberate copy of notification's class rather than a shared module: each consumer owns the
 * subset of the wire format it needs, so one service growing a field cannot force the others to
 * redeploy. (Unknown fields are ignored by Boot's ObjectMapper, which is what makes that safe.)
 *
 * [sourceEventId] is the ImageReceived it came from. Delivery is at-least-once, so the same
 * sourceEventId can arrive twice; here it is the detection table's primary key, so a redelivery
 * is absorbed by the insert rather than by a separate dedupe table.
 *
 * [recipient] is the hunter's inbound address, carried from email-ingestion untouched; it is how
 * a detection is attributed. [hunterId] is deprecated and null on new events, still read as a
 * fallback so an event published before that change (or replayed from a dead-letter topic) is
 * stored rather than thrown away.
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
