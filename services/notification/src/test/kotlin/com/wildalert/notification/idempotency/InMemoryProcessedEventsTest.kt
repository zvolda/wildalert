package com.wildalert.notification.idempotency

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class InMemoryProcessedEventsTest {

    private val events = InMemoryProcessedEvents()
    private val sourceEventId = UUID.randomUUID()

    @Test
    fun `the first claim wins and the second is refused`() {
        assertThat(events.claim(sourceEventId)).isTrue()
        assertThat(events.claim(sourceEventId)).isFalse()
    }

    @Test
    fun `different events are claimed independently`() {
        assertThat(events.claim(sourceEventId)).isTrue()
        assertThat(events.claim(UUID.randomUUID())).isTrue()
    }

    @Test
    fun `a released claim can be taken again`() {
        events.claim(sourceEventId)

        events.release(sourceEventId)

        assertThat(events.claim(sourceEventId)).isTrue()
    }

    @Test
    fun `only one of many concurrent claims succeeds`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val claims = pool.invokeAll((1..8).map { Callable { events.claim(sourceEventId) } })

            assertThat(claims.count { it.get() }).isEqualTo(1)
        } finally {
            pool.shutdown()
        }
    }
}
