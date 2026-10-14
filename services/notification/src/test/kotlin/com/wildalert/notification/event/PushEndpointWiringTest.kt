package com.wildalert.notification.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.TestPropertySource

/**
 * Guards the provider switch in both directions. Without these, `events.provider` could silently
 * stop wiring the push endpoint (so Pub/Sub would get 404s and every alert would be dead-lettered),
 * or start requiring a broker in local runs and tests.
 */
class PushEndpointWiringTest {

    @SpringBootTest
    @TestPropertySource(properties = ["events.provider=pubsub"])
    class WhenProviderIsPubSub {

        @Autowired
        private lateinit var context: ApplicationContext

        @Test
        fun `the push endpoint is wired`() {
            assertThat(context.getBeansOfType(AnimalRecognizedPushController::class.java)).hasSize(1)
        }
    }

    @SpringBootTest
    class WhenProviderIsUnset {

        @Autowired
        private lateinit var context: ApplicationContext

        @Test
        fun `there is no push endpoint and no broker is needed`() {
            assertThat(context.getBeansOfType(AnimalRecognizedPushController::class.java)).isEmpty()
        }
    }
}
