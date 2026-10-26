package com.wildalert.detectionhistory.history

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Tests the HTTP layer only: routing, JSON, paging defaults. Service is mocked.
 *
 * Stubbed with exact argument values rather than matchers wherever possible — that way each test
 * states the call it expects the controller to make, and an unstubbed call returns Mockito's empty
 * list, so a wrong call shows up as an empty response instead of a confusing matcher error.
 */
@WebMvcTest(DetectionController::class)
class DetectionControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var service: DetectionHistoryService

    private val recipient = "7f3k9qabcdef@wildalert.app"

    private fun detection(species: String = "wild boar") = Detection(
        sourceEventId = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"),
        recipient = recipient,
        hunterId = null,
        storageKey = "inbound/2026/10/20/photo.jpg",
        species = species,
        confidence = 0.97,
        lowConfidence = false,
        occurredAt = Instant.parse("2026-10-20T22:14:00Z"),
    )

    @Test
    fun `GET returns a hunter's detections`() {
        given(service.history(recipient, null, 50, 0)).willReturn(listOf(detection()))

        mockMvc.perform(get("/api/detections").param("recipient", recipient))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value("aaaaaaaa-0000-4000-8000-000000000001"))
            .andExpect(jsonPath("$[0].species").value("wild boar"))
            .andExpect(jsonPath("$[0].confidence").value(0.97))
            .andExpect(jsonPath("$[0].lowConfidence").value(false))
            .andExpect(jsonPath("$[0].occurredAt").value("2026-10-20T22:14:00Z"))
            .andExpect(jsonPath("$[0].storageKey").value("inbound/2026/10/20/photo.jpg"))
    }

    /** The hunter's own address is not echoed back in every row — see DetectionResponse. */
    @Test
    fun `the response does not repeat the hunter's address`() {
        given(service.history(recipient, null, 50, 0)).willReturn(listOf(detection()))

        mockMvc.perform(get("/api/detections").param("recipient", recipient))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].recipient").doesNotExist())
            .andExpect(jsonPath("$[0].hunterId").doesNotExist())
    }

    @Test
    fun `the species filter is passed through, trimmed`() {
        given(service.history(recipient, "roe deer", 50, 0)).willReturn(listOf(detection("roe deer")))

        mockMvc.perform(get("/api/detections").param("recipient", recipient).param("species", " roe deer "))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].species").value("roe deer"))
    }

    /** A blank species is an unset filter, not a search for the empty string. */
    @Test
    fun `a blank species is treated as no filter`() {
        given(service.history(recipient, null, 50, 0)).willReturn(listOf(detection()))

        mockMvc.perform(get("/api/detections").param("recipient", recipient).param("species", "  "))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].species").value("wild boar"))
    }

    /** A page size nobody should ask for is capped rather than rejected. */
    @Test
    fun `an oversized limit is clamped and a negative offset floored`() {
        mockMvc.perform(
            get("/api/detections")
                .param("recipient", recipient)
                .param("limit", "100000")
                .param("offset", "-5")
        ).andExpect(status().isOk)

        then(service).should().history(recipient, null, 200, 0)
    }

    @Test
    fun `a limit of zero is raised to one rather than returning nothing`() {
        mockMvc.perform(get("/api/detections").param("recipient", recipient).param("limit", "0"))
            .andExpect(status().isOk)

        then(service).should().history(recipient, null, 1, 0)
    }

    @Test
    fun `paging parameters reach the service unchanged when they are sensible`() {
        mockMvc.perform(
            get("/api/detections")
                .param("recipient", recipient)
                .param("limit", "20")
                .param("offset", "40")
        ).andExpect(status().isOk)

        then(service).should().history(recipient, null, 20, 40)
    }

    @Test
    fun `GET summary returns per-species counts`() {
        // An explicit `since` so the stub can use exact values instead of a matcher -- the default
        // window is Instant.now()-based and therefore unknowable from here. The default is checked
        // on its own below.
        val since = Instant.parse("2026-10-01T00:00:00Z")
        given(service.summary(recipient, since)).willReturn(
            listOf(
                SpeciesSummary(
                    species = "wild boar",
                    sightings = 7,
                    lastSeenAt = Instant.parse("2026-10-20T22:14:00Z"),
                    averageConfidence = 0.91,
                )
            )
        )

        mockMvc.perform(
            get("/api/detections/summary")
                .param("recipient", recipient)
                .param("since", "2026-10-01T00:00:00Z")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].species").value("wild boar"))
            .andExpect(jsonPath("$[0].sightings").value(7))
            .andExpect(jsonPath("$[0].lastSeenAt").value("2026-10-20T22:14:00Z"))
            .andExpect(jsonPath("$[0].averageConfidence").value(0.91))
    }

    @Test
    fun `summary accepts an explicit since instant`() {
        mockMvc.perform(
            get("/api/detections/summary")
                .param("recipient", recipient)
                .param("since", "2026-10-01T00:00:00Z")
        ).andExpect(status().isOk)

        then(service).should().summary(recipient, Instant.parse("2026-10-01T00:00:00Z"))
    }

    /** Without a `since`, the overview covers the last 30 days rather than all of history. */
    @Test
    fun `summary defaults to the last thirty days`() {
        val before = Instant.now()

        mockMvc.perform(get("/api/detections/summary").param("recipient", recipient))
            .andExpect(status().isOk)

        val since = ArgumentCaptor.forClass(Instant::class.java)
        then(service).should().summary(eqValue(recipient), capture(since))
        assertThat(since.value).isBetween(
            before.minus(THIRTY_DAYS).minusSeconds(60),
            Instant.now().minus(THIRTY_DAYS).plusSeconds(60),
        )
    }

    @Test
    fun `recipient is required`() {
        mockMvc.perform(get("/api/detections"))
            .andExpect(status().isBadRequest)
    }

    private companion object {
        private val THIRTY_DAYS = Duration.ofDays(30)

        // Mockito's matchers and captors return null, and the Kotlin compiler inserts a null check
        // at the call site when a non-null parameter of a *Kotlin* function is handed a nullable
        // expression -- so a plain `summary(eq(recipient), captor.capture())` throws
        // "eq(...) must not be null" before Mockito is ever consulted. By the time the matcher
        // call returns it has already registered itself, so substituting any non-null placeholder
        // for the discarded return value is safe. (Mocks of Java types need none of this: there
        // the null check lives in the Kotlin caller, which is absent.)
        private fun <T : Any> eqValue(value: T): T = ArgumentMatchers.eq(value) ?: value

        private fun capture(captor: ArgumentCaptor<Instant>): Instant =
            captor.capture() ?: Instant.EPOCH
    }
}
