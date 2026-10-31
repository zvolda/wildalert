package com.wildalert.useraccount.password

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Exercises the real RestClient wiring against a mock Resend, the way GoSmsSenderTest does for
 * SMS: the exact request shape, and the failures worth getting right.
 */
class ResendEmailSenderTest {

    private val props = ResendProperties(apiKey = "re_test_key", baseUrl = "https://resend.test")
    private val emailProps = EmailProperties(fromAddress = "WildAlert <no-reply@wildalert.test>")

    private val message = EmailMessage(
        to = "hunter@example.com",
        subject = "Reset your WildAlert password",
        body = "Open this link:\nhttps://wildalert.test/?reset=abc123",
    )

    @Test
    fun `posts the message with the bearer key and the configured from address`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        server.expect(requestTo("https://resend.test/emails"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test_key"))
            .andExpect(jsonPath("$.from").value("WildAlert <no-reply@wildalert.test>"))
            // A list even for one recipient, because that is Resend's shape.
            .andExpect(jsonPath("$.to[0]").value("hunter@example.com"))
            .andExpect(jsonPath("$.subject").value("Reset your WildAlert password"))
            .andExpect(jsonPath("$.text").value(message.body))
            // `html` is deliberately absent: a plain-text mail cannot have its link mangled.
            .andExpect(jsonPath("$.html").doesNotExist())
            .andRespond(withSuccess("""{"id":"4ef9a417-02e9-4d39-ad75-9611e0fcc33c"}""", MediaType.APPLICATION_JSON))

        ResendEmailSender(builder, props, emailProps).send(message)

        server.verify()
    }

    @Test
    fun `a rejected key propagates, so the caller can discard the token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        server.expect(requestTo("https://resend.test/emails"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val sender = ResendEmailSender(builder, props, emailProps)

        // Must throw rather than swallow: PasswordResetService catches it, throws the unsent
        // token away and still answers 202. Swallowing here would leave a live token for a mail
        // that never went, and the hunter throttled behind it.
        assertThatThrownBy { sender.send(message) }.isInstanceOf(RestClientException::class.java)
        server.verify()
    }

    @Test
    fun `a validation error from Resend propagates too`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        // 422 is what Resend answers for an unverified sending domain -- the single likeliest
        // failure on the first real deploy, before the DKIM records have propagated.
        server.expect(requestTo("https://resend.test/emails"))
            .andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body("""{"statusCode":422,"message":"The wildalert.app domain is not verified"}""")
                    .contentType(MediaType.APPLICATION_JSON),
            )

        assertThatThrownBy { ResendEmailSender(builder, props, emailProps).send(message) }
            .isInstanceOf(RestClientException::class.java)
    }

    @Test
    fun `a success with no id is not an error`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        server.expect(requestTo("https://resend.test/emails"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        // The id is only for finding the message in Resend's dashboard later. A 200 without one
        // still means accepted, and inventing a failure would discard a token whose mail is on
        // its way.
        ResendEmailSender(builder, props, emailProps).send(message)
        server.verify()
    }

    @Test
    fun `an empty api key fails at startup rather than at the first reset`() {
        val ex = assertThrows<IllegalArgumentException> {
            ResendEmailSender(RestClient.builder(), props.copy(apiKey = ""), emailProps)
        }
        assertThat(ex).hasMessageContaining("RESEND_API_KEY")
    }
}
