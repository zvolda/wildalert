package com.wildalert.emailingestion.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
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
}
