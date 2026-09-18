package com.wildalert.emailingestion.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ObjectKeysTest {

    // Fixed clock so the date-partitioned prefix is deterministic.
    private val clock = Clock.fixed(Instant.parse("2026-09-04T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `key is date-partitioned with a uuid name and content-type extension`() {
        val key = ObjectKeys.newKey(contentType = "image/png", filename = "boar.png", clock = clock)

        assertThat(key).matches("inbound/2026/09/04/[0-9a-f-]{36}\\.png")
    }

    @Test
    fun `jpeg content type is normalised to jpg`() {
        val key = ObjectKeys.newKey(contentType = "image/jpeg", filename = null, clock = clock)

        assertThat(key).endsWith(".jpg")
    }

    @Test
    fun `falls back to the filename extension when content type is missing`() {
        val key = ObjectKeys.newKey(contentType = null, filename = "trailcam.JPG", clock = clock)

        assertThat(key).endsWith(".jpg")
    }

    @Test
    fun `no extension when neither content type nor filename gives one`() {
        val key = ObjectKeys.newKey(contentType = null, filename = "attachment", clock = clock)

        assertThat(key).matches("inbound/2026/09/04/[0-9a-f-]{36}")
    }
}
