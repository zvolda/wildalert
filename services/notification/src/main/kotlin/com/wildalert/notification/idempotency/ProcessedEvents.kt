package com.wildalert.notification.idempotency

import java.util.UUID

/**
 * Remembers which recognition results have already been turned into an SMS.
 *
 * Delivery is at-least-once: if this service dies after sending an SMS but before its Kafka offset
 * is committed, the same event comes back. Without this, the hunter is texted twice and we pay
 * twice. Events are identified by their `sourceEventId` (the ImageReceived they came from), which
 * stays the same across redeliveries — unlike `eventId`, which is new for every published result.
 *
 * `claim` is called *before* sending, so two replicas handling the same event can't both send;
 * `release` undoes the claim when sending failed, so the retry is free to try again.
 */
interface ProcessedEvents {

    /** True if this event is ours to handle; false if it was already handled. */
    fun claim(sourceEventId: UUID): Boolean

    /** Gives up a claim after a failed send, so a later delivery of the same event retries. */
    fun release(sourceEventId: UUID)
}
