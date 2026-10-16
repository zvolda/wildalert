package com.wildalert.emailingestion.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.ConnectException
import java.util.UUID

/**
 * Exercises the real RestClient HTTP wiring (URL, method, JSON decoding, 404 handling) by
 * binding a MockRestServiceServer to the builder the client builds its RestClient from.
 */
class HunterLookupClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HunterLookupClient(builder, "http://user-account")

    @Test
    fun `findByInboundAddress returns the hunter when user-account responds 200`() {
        val id = UUID.randomUUID()
        server.expect(requestTo("http://user-account/api/hunters/by-inbound-address?address=7f3k9qabcdef@in.wildalert.local"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"id":"$id","email":"hunter@example.com"}""", MediaType.APPLICATION_JSON))

        val result = client.findByInboundAddress("7f3k9qabcdef@in.wildalert.local")

        assertThat(result).isEqualTo(HunterRef(id))
        server.verify()
    }

    @Test
    fun `findByInboundAddress returns null when user-account responds 404`() {
        server.expect(requestTo("http://user-account/api/hunters/by-inbound-address?address=hunter@gmail.com"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        val result = client.findByInboundAddress("hunter@gmail.com")

        assertThat(result).isNull()
        server.verify()
    }

    @Test
    fun `returns null when user-account cannot be reached at all, after retrying`() {
        // The webhook must not fail: losing the email is unrecoverable, missing one SMS is not.
        server.expect(ExpectedCount.twice(), requestTo(lookupUrl("hunter@in.wildalert.local")))
            .andRespond(withException(ConnectException("connection refused")))

        val result = client.findByInboundAddress("hunter@in.wildalert.local")

        assertThat(result).isNull()
        server.verify()
    }

    @Test
    fun `returns null when user-account answers with a server error`() {
        server.expect(ExpectedCount.twice(), requestTo(lookupUrl("hunter@in.wildalert.local")))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThat(client.findByInboundAddress("hunter@in.wildalert.local")).isNull()
        server.verify()
    }

    @Test
    fun `a retry recovers when user-account was only briefly unavailable`() {
        // The case this retry exists for: the first call hits an instance that is still starting.
        val id = UUID.randomUUID()
        server.expect(ExpectedCount.once(), requestTo(lookupUrl("hunter@in.wildalert.local")))
            .andRespond(withException(ConnectException("connection refused")))
        server.expect(ExpectedCount.once(), requestTo(lookupUrl("hunter@in.wildalert.local")))
            .andRespond(withSuccess("""{"id":"$id"}""", MediaType.APPLICATION_JSON))

        assertThat(client.findByInboundAddress("hunter@in.wildalert.local")).isEqualTo(HunterRef(id))
        server.verify()
    }

    @Test
    fun `a 404 is not retried - it is a definite answer`() {
        server.expect(ExpectedCount.once(), requestTo(lookupUrl("stranger@example.com")))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThat(client.findByInboundAddress("stranger@example.com")).isNull()
        server.verify()
    }

    @Test
    fun `no Authorization header when there is no UserAccountAuth - the local default`() {
        server.expect(requestTo(lookupUrl("hunter@in.wildalert.local")))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andRespond(withSuccess("""{"id":"${UUID.randomUUID()}"}""", MediaType.APPLICATION_JSON))

        client.findByInboundAddress("hunter@in.wildalert.local")

        server.verify()
    }

    @Test
    fun `sends the credential from UserAccountAuth when one is configured`() {
        // In the cloud this is the Google ID token that gets us past Cloud Run's IAM check.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val authorized = HunterLookupClient(builder, "http://user-account") { headers ->
            headers.setBearerAuth("fake-id-token")
        }
        server.expect(requestTo(lookupUrl("hunter@in.wildalert.local")))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-id-token"))
            .andRespond(withSuccess("""{"id":"${UUID.randomUUID()}"}""", MediaType.APPLICATION_JSON))

        authorized.findByInboundAddress("hunter@in.wildalert.local")

        server.verify()
    }

    @Test
    fun `a credential that cannot be minted degrades to no match instead of failing`() {
        // Same contract as an unreachable user-account: never fail the webhook, never lose the photo.
        var calls = 0
        val failing = HunterLookupClient(RestClient.builder(), "http://user-account") {
            calls++
            throw RestClientException("no ID token")
        }

        assertThat(failing.findByInboundAddress("hunter@in.wildalert.local")).isNull()
        assertThat(calls).isEqualTo(2)
    }

    private fun lookupUrl(address: String) =
        "http://user-account/api/hunters/by-inbound-address?address=$address"
}
