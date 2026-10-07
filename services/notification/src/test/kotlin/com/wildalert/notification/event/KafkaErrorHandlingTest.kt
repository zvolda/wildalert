package com.wildalert.notification.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KafkaErrorHandlingTest {

    @Test
    fun `dead-letter topic is the original topic plus the shared suffix`() {
        // The Python worker parks image.received failures the same way, so both services agree.
        assertThat(DeadLetterTopics.forTopic("animal.recognized")).isEqualTo("animal.recognized.dlt")
        assertThat(DeadLetterTopics.forTopic("image.received")).isEqualTo("image.received.dlt")
    }
}
