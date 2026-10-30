package com.wildalert.notification.sms

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SmsController::class)
class SmsControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var smsSender: SmsSender

    @MockitoBean
    lateinit var providerCredit: SmsProviderCreditGuard

    @Test
    fun `POST test sends the SMS and returns 202`() {
        // Data-class equality lets us stub with the exact expected message (no matchers).
        given(smsSender.send(SmsMessage("+420123456789", "A wild boar was detected")))
            .willReturn(SmsResult("fake-123"))

        mockMvc.postJson("""{"to":"+420123456789","body":"A wild boar was detected"}""")
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.messageId").value("fake-123"))
    }

    @Test
    fun `POST with an invalid number returns 400`() {
        mockMvc.postJson("""{"to":"12345","body":"hi"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET credit reports our own gateway balance in messages`() {
        val credit = ProviderCredit.Known(31521, "CZK")
        given(providerCredit.inspect()).willReturn(credit)
        given(providerCredit.messagesLeft(credit)).willReturn(262)

        mockMvc.perform(get("/api/sms/credit"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("KNOWN"))
            .andExpect(jsonPath("$.remainingMinor").value(31521))
            .andExpect(jsonPath("$.currency").value("CZK"))
            .andExpect(jsonPath("$.messagesLeft").value(262))
    }

    @Test
    fun `GET credit says so when the provider has no balance to report`() {
        given(providerCredit.inspect()).willReturn(null)

        mockMvc.perform(get("/api/sms/credit"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("NOT_REPORTED"))
    }

    private fun MockMvc.postJson(body: String) =
        perform(post("/api/sms/test").contentType(MediaType.APPLICATION_JSON).content(body))
}
