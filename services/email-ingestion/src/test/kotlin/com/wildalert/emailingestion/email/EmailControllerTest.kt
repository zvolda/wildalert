package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.event.EventPublisher
import com.wildalert.emailingestion.event.ImageReceived
import com.wildalert.emailingestion.storage.LoggingImageStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Real EmailParser + fake LoggingImageStore + a recording EventPublisher. **Nothing is mocked any
 * more**: this service no longer calls user-account, so picking the hunter's address out of an
 * email is pure string work against `inbound.email-domain`. There is no test config: these
 * tests run against the **real default** in `application.yml` (`wildalert.local`), which is why
 * [InboundDomainContractTest] exists to pin that default to the one user-account mints on.
 */
@WebMvcTest(EmailController::class)
@Import(EmailParser::class, LoggingImageStore::class, EmailControllerTest.RecordingEventPublisher::class)
class EmailControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var events: RecordingEventPublisher

    private val inbound = "7f3k9qabcdef@wildalert.local"
    private val keyPattern = "inbound/[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f-]+[.]png"

    @BeforeEach
    fun resetEvents() = events.published.clear()

    @Test
    fun `takes the recipient from the envelope address the webhook passes, stores and publishes`() {
        val raw = TestEmails.withImageAttachment(from = "noreply@camera-vendor.com", to = "hunter@gmail.com")

        mockMvc.perform(postEmail(raw).param("envelopeTo", inbound))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("noreply@camera-vendor.com"))
            .andExpect(jsonPath("$.matchedRecipient").value(inbound))
            .andExpect(jsonPath("$.imageCount").value(1))
            .andExpect(jsonPath("$.images[0].storageKey").value(org.hamcrest.Matchers.matchesRegex(keyPattern)))

        val event = events.published.single()
        assertThat(event.recipient).isEqualTo(inbound)
        assertThat(event.senderEmail).isEqualTo("noreply@camera-vendor.com")
        assertThat(event.storageKey).matches(keyPattern)
        // Deprecated and deliberately empty: notification resolves the hunter from the recipient.
        assertThat(event.hunterId).isNull()
    }

    @Test
    fun `auto-forwarded email uses the delivery header, though From and To are not ours`() {
        val raw = TestEmails.withImageAttachment(
            from = "noreply@camera-vendor.com",
            to = "hunter@gmail.com",
            headers = mapOf("Delivered-To" to inbound),
        )

        mockMvc.perform(postEmail(raw))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedRecipient").value(inbound))

        assertThat(events.published.single().recipient).isEqualTo(inbound)
    }

    @Test
    fun `the hunter's personal address is never put on the event`() {
        // Only our own domain travels downstream. The hunter's Gmail is in the email's To header
        // and must not leak into an event other services (and a dead-letter topic) can read.
        val raw = TestEmails.withImageAttachment(
            from = "noreply@camera-vendor.com",
            to = "hunter@gmail.com",
            headers = mapOf("Delivered-To" to inbound),
        )

        mockMvc.perform(postEmail(raw)).andExpect(status().isOk)

        assertThat(events.published.single().recipient).doesNotContain("gmail.com")
    }

    @Test
    fun `a matching From address alone does not identify the recipient`() {
        // Spoofed or hand-forwarded: From looks like an inbound address, but no recipient is ours.
        val raw = TestEmails.withImageAttachment(from = inbound, to = "someone@example.com")

        mockMvc.perform(postEmail(raw))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedRecipient").doesNotExist())

        assertThat(events.published.single().recipient).isNull()
    }

    @Test
    fun `an email on somebody else's domain still succeeds, with no recipient`() {
        val raw = TestEmails.withImageAttachment(from = "stranger@example.com", to = "someone@example.com")

        mockMvc.perform(postEmail(raw))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedRecipient").doesNotExist())
            .andExpect(jsonPath("$.imageCount").value(1))

        assertThat(events.published.single().recipient).isNull()
    }

    @Test
    fun `an unknown address on our own domain is passed on, not rejected here`() {
        // A behaviour change worth stating: this service can no longer tell a real hunter's token
        // from a made-up one, because it does not look anything up. It forwards the address and
        // notification answers "not a hunter" with a 404. Spam to our domain therefore still costs
        // a storage write and a recognition run -- see ROADMAP for where to filter that if it bites.
        val raw = TestEmails.withImageAttachment(from = "stranger@example.com", to = "nobody@wildalert.local")

        mockMvc.perform(postEmail(raw))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedRecipient").value("nobody@wildalert.local"))

        assertThat(events.published.single().recipient).isEqualTo("nobody@wildalert.local")
    }

    @Test
    fun `the domain match ignores case`() {
        val shouty = "7F3K9QABCDEF@WILDALERT.LOCAL"
        val raw = TestEmails.withImageAttachment(from = "noreply@camera-vendor.com", to = "hunter@gmail.com")

        mockMvc.perform(postEmail(raw).param("envelopeTo", shouty))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedRecipient").value(shouty))

        assertThat(events.published.single().recipient).isEqualTo(shouty)
    }

    private fun postEmail(raw: ByteArray): MockHttpServletRequestBuilder =
        post("/api/emails").contentType(MediaType.APPLICATION_OCTET_STREAM).content(raw)

    /** Test double that records published events so we can assert on them. */
    class RecordingEventPublisher : EventPublisher {
        val published = mutableListOf<ImageReceived>()
        override fun publish(event: ImageReceived) {
            published.add(event)
        }
    }
}
