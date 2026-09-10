package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.client.HunterLookupClient
import com.wildalert.emailingestion.client.HunterRef
import com.wildalert.emailingestion.storage.LoggingImageStore
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

// Uses the real EmailParser + fake LoggingImageStore (imported); the hunter lookup is mocked
// so no user-account service or network is needed.
@WebMvcTest(EmailController::class)
@Import(EmailParser::class, LoggingImageStore::class)
class EmailControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var hunterLookup: HunterLookupClient

    @Test
    fun `POST an email from a known sender returns the matched hunter`() {
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
            .andExpect(jsonPath("$.images[0].filename").value("boar.png"))
            .andExpect(jsonPath("$.images[0].storageKey").value(org.hamcrest.Matchers.matchesRegex("inbound/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.png")))
    }

    @Test
    fun `POST an email from an unknown sender still succeeds with no matched hunter`() {
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
    }
}
