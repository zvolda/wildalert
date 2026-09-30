package com.wildalert.notification.hunter

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException
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
}
