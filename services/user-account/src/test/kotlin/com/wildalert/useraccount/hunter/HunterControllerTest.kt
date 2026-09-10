package com.wildalert.useraccount.hunter

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/** Tests the HTTP layer only: routing, JSON, validation, error mapping. Service is mocked. */
@WebMvcTest(HunterController::class)
class HunterControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var service: HunterService

    @Test
    fun `POST creates a hunter and returns 201`() {
        val id = UUID.randomUUID()
        // The controller deserializes the JSON into an equal RegisterHunterRequest
        // (data class equality), so Mockito matches this exact value.
        val expectedRequest = RegisterHunterRequest("hunter@example.com", "+420123456789", Plan.FREE)
        given(service.register(expectedRequest)).willReturn(sampleHunter(id))

        mockMvc.postJson("""{"email":"hunter@example.com","phone":"+420123456789","plan":"FREE"}""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.email").value("hunter@example.com"))
    }

    @Test
    fun `POST with an invalid email returns 400`() {
        mockMvc.postJson("""{"email":"not-an-email","phone":"+420123456789"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.email").exists())
    }

    @Test
    fun `GET returns 404 when the hunter is missing`() {
        val id = UUID.randomUUID()
        willThrow(HunterNotFoundException(id)).given(service).getById(id)

        mockMvc.perform(get("/api/hunters/{id}", id))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET by-email returns the hunter when it exists`() {
        val id = UUID.randomUUID()
        given(service.getByEmail("hunter@example.com")).willReturn(sampleHunter(id))

        mockMvc.perform(get("/api/hunters/by-email").param("email", "hunter@example.com"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.email").value("hunter@example.com"))
    }

    @Test
    fun `GET by-email returns 404 when no hunter has that email`() {
        willThrow(HunterNotFoundByEmailException("nobody@example.com"))
            .given(service).getByEmail("nobody@example.com")

        mockMvc.perform(get("/api/hunters/by-email").param("email", "nobody@example.com"))
            .andExpect(status().isNotFound)
    }

    // --- helpers ---

    private fun MockMvc.postJson(body: String) =
        perform(post("/api/hunters").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun sampleHunter(id: UUID): Hunter {
        val hunter = Hunter("hunter@example.com", "+420123456789", Plan.FREE, active = true)
        // id and timestamps are normally set by Hibernate; set them here for the response.
        ReflectionTestUtils.setField(hunter, "id", id)
        ReflectionTestUtils.setField(hunter, "createdAt", Instant.now())
        ReflectionTestUtils.setField(hunter, "updatedAt", Instant.now())
        return hunter
    }
}
