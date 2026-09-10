package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.client.HunterLookupClient
import com.wildalert.emailingestion.client.HunterRef
import com.wildalert.emailingestion.event.EventPublisher
import com.wildalert.emailingestion.event.ImageReceived
import com.wildalert.emailingestion.storage.LoggingImageStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
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

    @BeforeEach
    fun resetEvents() = events.published.clear()

    @Test
    fun `POST an email from a known sender stores it, matches the hunter, and publishes an event`() {
        val hunterId = UUID.randomUUID()
        given(hunterLookup.findByEmail("hunter@example.com")).willReturn(HunterRef(hunterId))
        val raw = TestEmails.withImageAttachment(from = "hunter@example.com", filename = "boar.png")

        mockMvc.perform(
            post("/api/emails")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(raw),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("hunter@example.com"))
            .andExpect(jsonPath("$.matchedHunterId").value(hunterId.toString()))
            .andExpect(jsonPath("$.imageCount").value(1))
            .andExpect(jsonPath("$.images[0].storageKey").value(org.hamcrest.Matchers.matchesRegex("inbound/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.png")))

        assertThat(events.published).hasSize(1)
        val event = events.published.single()
        assertThat(event.hunterId).isEqualTo(hunterId)
        assertThat(event.senderEmail).isEqualTo("hunter@example.com")
        assertThat(event.storageKey).matches("inbound/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.png")
    }

    @Test
    fun `POST an email from an unknown sender still succeeds and publishes an event with no hunter`() {
        given(hunterLookup.findByEmail("stranger@example.com")).willReturn(null)
        val raw = TestEmails.withImageAttachment(from = "stranger@example.com", filename = "boar.png")

        mockMvc.perform(
            post("/api/emails")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(raw),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchedHunterId").doesNotExist())
            .andExpect(jsonPath("$.imageCount").value(1))

        assertThat(events.published).hasSize(1)
        assertThat(events.published.single().hunterId).isNull()
    }

    /** Test double that records published events so we can assert on them. */
    class RecordingEventPublisher : EventPublisher {
        val published = mutableListOf<ImageReceived>()
        override fun publish(event: ImageReceived) {
            published.add(event)
        }
    }
}
