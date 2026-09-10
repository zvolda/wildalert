package com.wildalert.notification.sms

import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SmsController::class)
class SmsControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var smsSender: SmsSender

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

    private fun MockMvc.postJson(body: String) =
        perform(post("/api/sms/test").contentType(MediaType.APPLICATION_JSON).content(body))
}
