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
}
