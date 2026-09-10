package com.wildalert.emailingestion.event

import java.time.Instant
import java.util.UUID

/**
 * Emitted once per stored image after a forwarded email is ingested. The Recognition service
 * consumes this to classify the animal. Carries where the image lives (storageKey) plus the
 * sender/hunter context so downstream services don't have to re-derive it.
 *
 * eventId gives each event a stable identity for idempotency/deduplication once a real broker
 * with retries is in place (Phase 5).
 */
data class ImageReceived(
    val storageKey: String,
    val contentType: String?,
    val senderEmail: String?,
    val hunterId: UUID?,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)
