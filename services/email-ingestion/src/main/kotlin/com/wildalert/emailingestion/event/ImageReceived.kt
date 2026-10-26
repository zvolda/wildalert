package com.wildalert.emailingestion.event

import java.time.Instant
import java.util.UUID

/**
 * Emitted once per stored image after a forwarded email is ingested. The Recognition service
 * consumes this to classify the animal. Carries where the image lives (storageKey) plus the
 * context downstream services need so they don't have to re-derive it.
 *
 * [recipient] is the hunter's inbound address (`<token>@<inbound domain>`) that the email was
 * sent to — **who** the alert is for, resolved to an actual hunter by notification rather than
 * here. Resolving it here meant a synchronous call to user-account inside the webhook, and a
 * scaled-to-zero user-account then cost the alert silently: see ROADMAP and Phase 6 slice 1.
 * Null when the email reached us on an address that is not on our inbound domain.
 *
 * [hunterId] is **deprecated** and always null now. It is kept for one release so a consumer
 * deployed before this change still works, and so anything already sitting in a dead-letter
 * topic still parses. Remove it once every consumer reads [recipient].
 *
 * eventId gives each event a stable identity for idempotency/deduplication.
 */
data class ImageReceived(
    val storageKey: String,
    val contentType: String?,
    val senderEmail: String?,
    val recipient: String?,
    val hunterId: UUID? = null,
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: Instant = Instant.now(),
)
