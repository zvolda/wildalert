package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.storage.LoggingImageStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Uses the real EmailParser + fake LoggingImageStore (imported) — no mocking needed.
@WebMvcTest(EmailController::class)
@Import(EmailParser::class, LoggingImageStore::class)
class EmailControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `POST an email returns the extracted summary`() {
        val raw = TestEmails.withImageAttachment(from = "hunter@example.com", filename = "boar.png")

        mockMvc.perform(
            post("/api/emails")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(raw),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("hunter@example.com"))
            .andExpect(jsonPath("$.imageCount").value(1))
            .andExpect(jsonPath("$.images[0].filename").value("boar.png"))
            .andExpect(jsonPath("$.images[0].storageKey").value(org.hamcrest.Matchers.matchesRegex("inbound/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.png")))
    }
}
