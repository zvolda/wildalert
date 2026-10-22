package com.wildalert.notification.hunter

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClient
import java.util.UUID

class HunterClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HunterClient(builder, "http://user-account")

    private val id = UUID.randomUUID()

    @Test
    fun `findById returns the hunter's contact details, ignoring other fields`() {
        server.expect(requestTo("http://user-account/api/hunters/$id"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"id":"$id","email":"hunter@example.com","phone":"+420123456789","plan":"FREE","active":true}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertThat(client.findById(id)).isEqualTo(HunterContact(id, "+420123456789", active = true))
        server.verify()
    }

    @Test
    fun `findById returns null when the hunter does not exist`() {
        server.expect(requestTo("http://user-account/api/hunters/$id"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThat(client.findById(id)).isNull()
        server.verify()
    }

    @Test
    fun `server errors are thrown so the event can be retried`() {
        server.expect(requestTo("http://user-account/api/hunters/$id"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThatThrownBy { client.findById(id) }.isInstanceOf(HttpServerErrorException::class.java)
    }

    @Test
    fun `no Authorization header when there is no UserAccountAuth - the local default`() {
        server.expect(requestTo("http://user-account/api/hunters/$id"))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andRespond(
                withSuccess(
                    """{"id":"$id","phone":"+420123456789","active":true}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        client.findById(id)

        server.verify()
    }

    @Test
    fun `sends the credential from UserAccountAuth when one is configured`() {
        // In the cloud this is the Google ID token that gets us past Cloud Run's IAM check on
        // user-account. Without it every lookup 403s and the alert is dead-lettered.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val authorized = HunterClient(builder, "http://user-account") { headers ->
            headers.setBearerAuth("fake-id-token")
        }
        server.expect(requestTo("http://user-account/api/hunters/$id"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-id-token"))
            .andRespond(
                withSuccess(
                    """{"id":"$id","phone":"+420123456789","active":true}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        authorized.findById(id)

        server.verify()
    }

    @Test
    fun `a credential that cannot be minted is thrown so the event is retried`() {
        // The opposite contract to email-ingestion, deliberately: there, a missing token must not
        // fail the webhook (the photo would be lost). Here the photo is already stored, so failing
        // the push is right -- Pub/Sub redelivers and the SMS still goes out once the token works.
        val failing = HunterClient(RestClient.builder(), "http://user-account") {
            throw RestClientException("no ID token")
        }

        assertThatThrownBy { failing.findById(id) }
            .isInstanceOf(RestClientException::class.java)
    }
}
