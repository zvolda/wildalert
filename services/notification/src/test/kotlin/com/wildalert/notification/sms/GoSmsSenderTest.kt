package com.wildalert.notification.sms

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
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
 * Exercises the real RestClient wiring against a mock GoSMS: the OAuth2 exchange, the message
 * payload, token caching, and the two failure modes worth getting right (a rejected token, and
 * a response we cannot parse).
 */
class GoSmsSenderTest {

    private val props = GoSmsProperties(
        clientId = "client-123",
        clientSecret = "secret-456",
        channel = 42,
        baseUrl = "https://gosms.test",
    )

    private val message = SmsMessage(to = "+420123456789", body = "WildAlert: wild boar detected")

    private fun tokenResponse(token: String, expiresIn: Int = 3600) =
        withSuccess("""{"access_token":"$token","expires_in":$expiresIn}""", MediaType.APPLICATION_JSON)

    @Test
    fun `exchanges the client credentials for a token and posts the message`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        server.expect(requestTo("https://gosms.test/oauth/v2/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(tokenResponse("tok-abc"))

        server.expect(requestTo("https://gosms.test/api/v1/messages/"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-abc"))
            .andExpect(jsonPath("$.message").value("WildAlert: wild boar detected"))
            .andExpect(jsonPath("$.recipients[0]").value("+420123456789"))
            .andExpect(jsonPath("$.channel").value(42))
            .andRespond(withSuccess("""{"id":987}""", MediaType.APPLICATION_JSON))

        val result = GoSmsSender(builder, props).send(message)

        assertThat(result.messageId).isEqualTo("987")
        server.verify()
    }

    @Test
    fun `reuses the cached token across sends, so one token serves many alerts`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        // Exactly once: a second token request would mean we pay a round trip per SMS.
        server.expect(ExpectedCount.once(), requestTo("https://gosms.test/oauth/v2/token"))
            .andRespond(tokenResponse("tok-abc"))
        server.expect(ExpectedCount.twice(), requestTo("https://gosms.test/api/v1/messages/"))
            .andRespond(withSuccess("""{"id":1}""", MediaType.APPLICATION_JSON))

        val sender = GoSmsSender(builder, props)
        sender.send(message)
        sender.send(message)

        server.verify()
    }

    @Test
    fun `fetches a new token when the cached one has expired`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        // expires_in below the safety margin means the cached token is never considered fresh.
        server.expect(ExpectedCount.twice(), requestTo("https://gosms.test/oauth/v2/token"))
            .andRespond(tokenResponse("tok-short", expiresIn = 0))
        server.expect(ExpectedCount.twice(), requestTo("https://gosms.test/api/v1/messages/"))
            .andRespond(withSuccess("""{"id":1}""", MediaType.APPLICATION_JSON))

        val sender = GoSmsSender(builder, props)
        sender.send(message)
        sender.send(message)

        server.verify()
    }

    @Test
    fun `refreshes the token and retries once when GoSMS rejects it`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()

        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("stale"))
        server.expect(requestTo("https://gosms.test/api/v1/messages/"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("fresh"))
        server.expect(requestTo("https://gosms.test/api/v1/messages/"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh"))
            .andRespond(withSuccess("""{"id":5}""", MediaType.APPLICATION_JSON))

        assertThat(GoSmsSender(builder, props).send(message).messageId).isEqualTo("5")
        server.verify()
    }

    @Test
    fun `an id under data is read too, since the shape differs per endpoint`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/messages/"))
            .andRespond(withSuccess("""{"data":{"id":"abc-1"}}""", MediaType.APPLICATION_JSON))

        assertThat(GoSmsSender(builder, props).send(message).messageId).isEqualTo("abc-1")
    }

    @Test
    fun `the id is taken from link, which is what a real send actually returns`() {
        // Verbatim from the first real send (2026-10-26): no id field anywhere, the id is the last
        // segment of "link". Without this the happy path logged a WARN on every single SMS.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/messages/"))
            .andRespond(
                withSuccess(
                    """{"recipients":{"invalid":[],"optedOut":[]},"link":"/api/v1/messages/47102867094"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(GoSmsSender(builder, props).send(message).messageId).isEqualTo("47102867094")
    }

    @Test
    fun `an unrecognised response still counts as sent, because the gateway accepted it`() {
        // Failing here would redeliver an SMS the hunter has already received. The id is only
        // ever logged, so losing it is cheaper than sending twice.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/messages/"))
            .andRespond(withSuccess("""{"status":"queued"}""", MediaType.APPLICATION_JSON))

        assertThat(GoSmsSender(builder, props).send(message).messageId).isEqualTo("unknown")
    }

    @Test
    fun `reads our own prepaid balance from the account endpoint, verbatim as GoSMS answers it`() {
        // Captured live on 2026-10-30 against the real account. The field is currentCredit, in CZK
        // major units -- the earlier note in STATUS.md called it "credit", which it is not.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andRespond(
                withSuccess(
                    """{"currentCredit":315.21,"invoicingType":"Prepaid","currency":"CZK",""" +
                        """"channels":[{"id":511762,"name":"SMS channel","sourceNumber":"GoSMS-test"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val credit = GoSmsSender(builder, props).remaining()

        assertThat(credit).isEqualTo(ProviderCredit.Known(31521, "CZK"))
        server.verify()
    }

    @Test
    fun `the credit read shares the cached token with sending`() {
        // Which is the whole reason the two live in one class: guarding the balance must not cost
        // an OAuth round trip per check.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.once(), requestTo("https://gosms.test/oauth/v2/token"))
            .andRespond(tokenResponse("tok-abc"))
        server.expect(requestTo("https://gosms.test/api/v1/messages/"))
            .andRespond(withSuccess("""{"id":1}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andRespond(withSuccess("""{"currentCredit":10,"invoicingType":"Prepaid","currency":"CZK"}""", MediaType.APPLICATION_JSON))

        val sender = GoSmsSender(builder, props)
        sender.send(message)
        sender.remaining()

        server.verify()
    }

    @Test
    fun `a postpaid account has no balance to run out of`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andRespond(
                withSuccess(
                    """{"currentCredit":0,"invoicingType":"Postpaid","currency":"CZK"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(GoSmsSender(builder, props).remaining())
            .isEqualTo(ProviderCredit.Unmetered("Postpaid"))
    }

    @Test
    fun `a balance in another currency is not compared against CZK prices`() {
        // Our prices are haléře. Treating 20 EUR as 2000 haléře would under-read the balance by a
        // factor of 25 and block alerts the account could easily pay for.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andRespond(
                withSuccess(
                    """{"currentCredit":20.5,"invoicingType":"Prepaid","currency":"EUR"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(GoSmsSender(builder, props).remaining())
            .isInstanceOf(ProviderCredit.Unknown::class.java)
    }

    @Test
    fun `a response without the fields we need is unknown, not zero`() {
        // The one mistake that must not happen: reading a changed response as an empty balance
        // would stop every alert in the product.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andRespond(withSuccess("""{"balance":"plenty"}""", MediaType.APPLICATION_JSON))

        assertThat(GoSmsSender(builder, props).remaining())
            .isInstanceOf(ProviderCredit.Unknown::class.java)
    }

    @Test
    fun `a non-numeric credit is unknown too`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("tok"))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andRespond(
                withSuccess(
                    """{"currentCredit":"315.21","invoicingType":"Prepaid","currency":"CZK"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(GoSmsSender(builder, props).remaining())
            .isInstanceOf(ProviderCredit.Unknown::class.java)
    }

    @Test
    fun `the credit read refreshes a rejected token and retries once, like sending does`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("stale"))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))
        server.expect(requestTo("https://gosms.test/oauth/v2/token")).andRespond(tokenResponse("fresh"))
        server.expect(requestTo("https://gosms.test/api/v1/"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh"))
            .andRespond(
                withSuccess(
                    """{"currentCredit":1.2,"invoicingType":"Prepaid","currency":"CZK"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(GoSmsSender(builder, props).remaining())
            .isEqualTo(ProviderCredit.Known(120, "CZK"))
        server.verify()
    }

    @Test
    fun `a token response with no access_token fails loudly`() {
        // Wrong credentials or a changed contract: throw, so the push 5xxs and Pub/Sub retries
        // rather than silently dropping the hunter's alert.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://gosms.test/oauth/v2/token"))
            .andRespond(withSuccess("""{"error":"invalid_client"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { GoSmsSender(builder, props).send(message) }
            .isInstanceOf(RestClientException::class.java)
    }
}
