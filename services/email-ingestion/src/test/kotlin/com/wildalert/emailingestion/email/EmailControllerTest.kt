package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.client.HunterLookupClient
import com.wildalert.emailingestion.client.HunterRef
import com.wildalert.emailingestion.event.EventPublisher
import com.wildalert.emailingestion.event.ImageReceived
import com.wildalert.emailingestion.storage.LoggingImageStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.never
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

// Real EmailParser + fake LoggingImageStore + a recording EventPublisher (all imported); the
// hunter lookup is mocked so no user-account service or network is needed.
@WebMvcTest(EmailController::class)
@Import(EmailParser::class, LoggingImageStore::class, EmailControllerTest.RecordingEventPublisher::class)
class EmailControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var hunterLookup: HunterLookupClient

    @Autowired
    lateinit var events: RecordingEventPublisher

    private val inbound = "7f3k9qabcdef@in.wildalert.local"
    private val hunterId = UUID.randomUUID()

    @BeforeEach
    fun resetEvents() = events.published.clear()

    @Test
    fun `matches the hunter by the envelope recipient passed by the webhook, stores and publishes`() {
        given(hunterLookup.findByInboundAddress(inbound)).willReturn(HunterRef(hunterId))
        val raw = TestEmails.withImageAttachment(from = "noreply@camera-vendor.com", to = "hunter@gmail.com")

        mockMvc.perform(postEmail(raw).param("envelopeTo", inbound))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("noreply@camera-vendor.com"))
            .andExpect(jsonPath("$.matchedRecipient").value(inbound))
            .andExpect(jsonPath("$.matchedHunterId").value(hunterId.toString()))
            .andExpect(jsonPath("$.imageCount").value(1))
            .andExpect(jsonPath("$.images[0].storageKey").value(org.hamcrest.Matchers.matchesRegex("inbound/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.png")))

        val event = events.published.single()
        assertThat(event.hunterId).isEqualTo(hunterId)
        assertThat(event.senderEmail).isEqualTo("noreply@camera-vendor.com")
        assertThat(event.storageKey).matches("inbound/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.png")
        // Envelope matched first, so the hunter's own address was never looked up.
        then(hunterLookup).should(never()).findByInboundAddress("hunter@gmail.com")
    }

    @Test
    fun `auto-forwarded email matches by delivery header even though From and To are not ours`() {
        given(hunterLookup.findByInboundAddress(anyString())).willReturn(null)
        given(hunterLookup.findByInboundAddress(inbound)).willReturn(HunterRef(hunterId))
        val raw = TestEmails.withImageAttachment(
            from = "noreply@camera-vendor.com",
            to = "hunter@gmail.com",
            headers = mapOf("Delivered-To" to inbound),
        )

        mockMvc.perform(postEmail(raw))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedHunterId").value(hunterId.toString()))

        assertThat(events.published.single().hunterId).isEqualTo(hunterId)
    }

    @Test
    fun `a matching From address alone does not identify the hunter`() {
        given(hunterLookup.findByInboundAddress(anyString())).willReturn(null)
        // Spoofed or forwarded by hand: From looks like the hunter's inbound address, recipient is not.
        val raw = TestEmails.withImageAttachment(from = inbound, to = "someone@example.com")

        mockMvc.perform(postEmail(raw))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedHunterId").doesNotExist())

        then(hunterLookup).should(never()).findByInboundAddress(inbound)
    }

    @Test
    fun `unmatched email still succeeds and publishes an event with no hunter`() {
        given(hunterLookup.findByInboundAddress(anyString())).willReturn(null)
        val raw = TestEmails.withImageAttachment(from = "stranger@example.com", to = "random@in.wildalert.local")

        mockMvc.perform(postEmail(raw))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedRecipient").doesNotExist())
            .andExpect(jsonPath("$.matchedHunterId").doesNotExist())
            .andExpect(jsonPath("$.imageCount").value(1))

        assertThat(events.published.single().hunterId).isNull()
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
