package com.wildalert.useraccount.credit

import com.wildalert.useraccount.hunter.HunterNotFoundByInboundAddressException
import com.wildalert.useraccount.hunter.HunterService
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** The HTTP layer of the charge endpoints: routing, JSON, validation, error mapping. */
@WebMvcTest(ChargeController::class)
class ChargeControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var hunters: HunterService

    @MockitoBean
    lateinit var charges: CreditChargeService

    @Test
    fun `an address that is not a hunter's returns 404 and charges nothing`() {
        willThrow(HunterNotFoundByInboundAddressException("stranger@wildalert.local"))
            .given(hunters).getByInboundAddress("stranger@wildalert.local")

        mockMvc.perform(
            post("/api/hunters/by-inbound-address/charges")
                .param("address", "stranger@wildalert.local")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()),
        ).andExpect(status().isNotFound)

        // Mail to a made-up name on our domain must cost nobody anything.
        then(charges).shouldHaveNoInteractions()
    }

    @Test
    fun `a charge returns the outcome, the balance and the notice`() {
        val id = UUID.randomUUID()
        // Stubbed with concrete values rather than any() matchers: Mockito's any() returns null,
        // which blows up on these non-null Kotlin parameters. ChargeRequest is a data class, so
        // the deserialized body matches this by equality.
        val hunter = org.mockito.Mockito.mock(com.wildalert.useraccount.hunter.Hunter::class.java)
        given(hunters.getByInboundAddress("abc@wildalert.local")).willReturn(hunter)
        given(charges.charge(hunter, ChargeRequest("evt-1", 250, EntryType.SMS_DEBIT))).willReturn(
            ChargeResponse(
                hunterId = id,
                phone = "+420123456789",
                active = true,
                outcome = ChargeOutcome.CHARGED,
                balanceAfterMinor = 2250,
                notice = Notice.NONE,
            ),
        )

        mockMvc.perform(
            post("/api/hunters/by-inbound-address/charges")
                .param("address", "abc@wildalert.local")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hunterId").value(id.toString()))
            .andExpect(jsonPath("$.outcome").value("CHARGED"))
            .andExpect(jsonPath("$.balanceAfterMinor").value(2250))
            .andExpect(jsonPath("$.notice").value("NONE"))
    }

    @Test
    fun `a charge with no event id is rejected`() {
        // The event id is the idempotency key. Without it a redelivery would be charged again,
        // so an empty one is a bad request rather than something to tolerate.
        mockMvc.perform(
            post("/api/hunters/by-inbound-address/charges")
                .param("address", "abc@wildalert.local")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sourceEventId":"","amountMinor":250,"entryType":"SMS_DEBIT"}"""),
        ).andExpect(status().isBadRequest)

        then(charges).shouldHaveNoInteractions()
    }

    @Test
    fun `a charge of zero or less is rejected`() {
        mockMvc.perform(
            post("/api/hunters/by-inbound-address/charges")
                .param("address", "abc@wildalert.local")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"sourceEventId":"evt-1","amountMinor":0,"entryType":"SMS_DEBIT"}"""),
        ).andExpect(status().isBadRequest)

        then(charges).shouldHaveNoInteractions()
    }

    private fun body() =
        """{"sourceEventId":"evt-1","amountMinor":250,"entryType":"SMS_DEBIT"}"""

}
