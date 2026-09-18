package com.wildalert.emailingestion.event

/**
 * Abstraction over event publishing. Everything talks to this interface, so we can swap the
 * fake logging publisher for a real broker (Kafka) in Phase 5 without touching callers —
 * the same pattern as ImageStore (logging/R2) and SmsSender (logging/Twilio).
 */
interface EventPublisher {
    fun publish(event: ImageReceived)
}
