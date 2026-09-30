package com.wildalert.notification.idempotency

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fake ProcessedEvents kept in memory. Active by default (idempotency.store=memory or unset), so
 * the service runs with no database.
 *
 * It only protects one running instance: the memory is lost on restart and not shared between
 * replicas, so production must use the Postgres store.
 */
@Component
@ConditionalOnProperty(name = ["idempotency.store"], havingValue = "memory", matchIfMissing = true)
class InMemoryProcessedEvents : ProcessedEvents {

    private val handled = ConcurrentHashMap.newKeySet<UUID>()

    override fun claim(sourceEventId: UUID): Boolean = handled.add(sourceEventId)

    override fun release(sourceEventId: UUID) {
        handled.remove(sourceEventId)
    }
}
