package com.wildalert.useraccount.hunter

import com.wildalert.useraccount.credit.CreditService
import com.wildalert.useraccount.session.SessionCookies
import com.wildalert.useraccount.session.SessionService
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Tests the HTTP layer only: routing, JSON, validation, error mapping. Service is mocked.
 *
 * `addFilters = false` because Spring Security is now on the classpath, and with it every request
 * through MockMvc goes through the default filter chain. These tests are about controller mapping
 * and DTO shape, not authorization -- SecurityConfigTest covers the chain, where a failure is
 * about the chain rather than hidden behind an unrelated 401 here.
 */
@WebMvcTest(HunterController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(InboundAddresses::class, HunterResponseFactory::class, SessionCookies::class)
class HunterControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var service: HunterService

    @MockitoBean
    lateinit var credit: CreditService

    @MockitoBean
    lateinit var sessions: SessionService

    @Test
    fun `POST creates a hunter and returns 201`() {
        val id = UUID.randomUUID()
        val hunter = sampleHunter(id)
        // The controller deserializes the JSON into an equal RegisterHunterRequest
        // (data class equality), so Mockito matches this exact value.
        val expectedRequest = RegisterHunterRequest("hunter@example.com", "+420123456789", "correct-horse")
        given(service.register(expectedRequest)).willReturn(hunter)
        given(credit.balanceOf(id)).willReturn(2500)
        given(sessions.open(hunter)).willReturn("a-session-token")
        given(sessions.ttl).willReturn(Duration.ofDays(30))

        mockMvc.postJson(
            """{"email":"hunter@example.com","phone":"+420123456789","password":"correct-horse"}""",
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.email").value("hunter@example.com"))
            // Default domain from application.yml (INBOUND_EMAIL_DOMAIN unset).
            .andExpect(jsonPath("$.inboundAddress").value("7f3k9qabcdef@wildalert.local"))
            // Haléře, not a formatted string -- the client owns the formatting.
            .andExpect(jsonPath("$.creditBalanceMinor").value(2500))
            // The default from application.yml: recognition (100) + SMS (150).
            .andExpect(jsonPath("$.alertPriceMinor").value(250))
            // The password must never come back out, under any name.
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
    }

    @Test
    fun `registering logs the hunter straight in, so they never retype the password they just chose`() {
        val id = UUID.randomUUID()
        val hunter = sampleHunter(id)
        given(service.register(RegisterHunterRequest("hunter@example.com", "+420123456789", "correct-horse")))
            .willReturn(hunter)
        given(credit.balanceOf(id)).willReturn(2500)
        given(sessions.open(hunter)).willReturn("a-session-token")
        given(sessions.ttl).willReturn(Duration.ofDays(30))

        mockMvc.postJson(
            """{"email":"hunter@example.com","phone":"+420123456789","password":"correct-horse"}""",
        )
            .andExpect(status().isCreated)
            .andExpect(
                header().string(
                    HttpHeaders.SET_COOKIE,
                    // HttpOnly so script cannot read it; Lax so a cross-site POST cannot ride it;
                    // not Secure because application.yml defaults that to false for local http.
                    allOf(
                        containsString("wa_session=a-session-token"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Lax"),
                        containsString("Path=/"),
                    ),
                ),
            )
    }

    @Test
    fun `POST with an empty password returns 400 against the password field`() {
        mockMvc.postJson("""{"email":"hunter@example.com","phone":"+420123456789","password":""}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.password").exists())
    }

    @Test
    fun `POST with the password key missing entirely returns 400`() {
        // Note the difference from the test above, which surprised me and is worth recording:
        // an *absent* key fails in Jackson, before bean validation runs, because `password` is a
        // non-null Kotlin property. So this is a plain 400 with no `errors` map, where an empty
        // string gets a per-field message. Pre-existing behaviour for every required field here,
        // not something the password introduced -- and harmless, because the React form always
        // sends all three keys. It would matter for a hand-written API client.
        mockMvc.postJson("""{"email":"hunter@example.com","phone":"+420123456789"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST with a password longer than BCrypt can see returns 400`() {
        // 73 characters. BCrypt truncates at 72 bytes, so accepting this would mean the hunter's
        // 73rd character is decoration and a shorter password would open their account.
        val tooLong = "a".repeat(73)
        mockMvc.postJson(
            """{"email":"hunter@example.com","phone":"+420123456789","password":"$tooLong"}""",
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.password").exists())
    }

    @Test
    fun `the response reports the balance in minor units, even when it is zero`() {
        val id = UUID.randomUUID()
        given(service.getById(id)).willReturn(sampleHunter(id))
        given(credit.balanceOf(id)).willReturn(0)

        mockMvc.perform(get("/api/hunters/{id}", id))
            .andExpect(status().isOk)
            // Must be present and 0, not absent: a missing field reads as "unknown" to a client.
            .andExpect(jsonPath("$.creditBalanceMinor").value(0))
    }

    @Test
    fun `GET by-inbound-address returns the hunter who owns the address`() {
        val id = UUID.randomUUID()
        given(service.getByInboundAddress("7f3k9qabcdef@wildalert.local")).willReturn(sampleHunter(id))

        mockMvc.perform(get("/api/hunters/by-inbound-address").param("address", "7f3k9qabcdef@wildalert.local"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.inboundAddress").value("7f3k9qabcdef@wildalert.local"))
    }

    @Test
    fun `GET by-inbound-address returns 404 for an address that is not a hunter's`() {
        willThrow(HunterNotFoundByInboundAddressException("hunter@gmail.com"))
            .given(service).getByInboundAddress("hunter@gmail.com")

        mockMvc.perform(get("/api/hunters/by-inbound-address").param("address", "hunter@gmail.com"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST with an invalid email returns 400`() {
        mockMvc.postJson(
            """{"email":"not-an-email","phone":"+420123456789","password":"correct-horse"}""",
        )
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
        val hunter = Hunter("hunter@example.com", "+420123456789", active = true)
        // id and timestamps are normally set by Hibernate; set them here for the response.
        ReflectionTestUtils.setField(hunter, "id", id)
        ReflectionTestUtils.setField(hunter, "inboundToken", "7f3k9qabcdef")
        ReflectionTestUtils.setField(hunter, "createdAt", Instant.now())
        ReflectionTestUtils.setField(hunter, "updatedAt", Instant.now())
        return hunter
    }
}
