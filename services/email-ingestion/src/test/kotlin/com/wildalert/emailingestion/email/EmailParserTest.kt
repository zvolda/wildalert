package com.wildalert.emailingestion.email

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmailParserTest {

    private val parser = EmailParser()

    @Test
    fun `extracts sender, subject and image attachment`() {
        val raw = TestEmails.withImageAttachment(
            from = "hunter@example.com",
            subject = "Trail cam photo",
            filename = "boar.png",
        )

        val result = parser.parse(raw)

        assertThat(result.from).isEqualTo("hunter@example.com")
        assertThat(result.subject).isEqualTo("Trail cam photo")
        assertThat(result.images).hasSize(1)
        assertThat(result.images[0].filename).isEqualTo("boar.png")
        assertThat(result.images[0].contentType).isEqualTo("image/png")
        assertThat(result.images[0].bytes).isNotEmpty()
    }

    @Test
    fun `lists recipients with delivery headers before To and Cc, without duplicates`() {
        // What an automatically forwarded camera email looks like when it reaches us: From and To
        // are unchanged from the original, only the delivery headers name our inbound address.
        val raw = TestEmails.withImageAttachment(
            from = "noreply@camera-vendor.com",
            to = "hunter@gmail.com, 7F3K9QABCDEF@in.wildalert.local",
            headers = mapOf(
                "Delivered-To" to "7f3k9qabcdef@in.wildalert.local",
                "X-Original-To" to "<other@in.wildalert.local>",
                "Cc" to "friend@example.com",
            ),
        )

        val result = parser.parse(raw)

        assertThat(result.recipients).containsExactly(
            "7f3k9qabcdef@in.wildalert.local",
            "other@in.wildalert.local",
            "hunter@gmail.com",
            "friend@example.com",
        )
    }

    @Test
    fun `an email with no recipients has an empty list`() {
        assertThat(parser.parse(TestEmails.withImageAttachment()).recipients).isEmpty()
    }
}
