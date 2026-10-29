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
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.util.UUID

class HunterClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = HunterClient(builder, "http://user-account")

    private val id = UUID.randomUUID()
    private val address = "7f3k9qabcdef@wildalert.local"
    private val request = ChargeRequest(sourceEventId = SOURCE_EVENT, amountMinor = 150)

    @Test
    fun `charging by address posts the price and maps the answer`() {
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("\$.sourceEventId").value(SOURCE_EVENT))
            .andExpect(jsonPath("\$.amountMinor").value(150))
            .andExpect(jsonPath("\$.entryType").value("SMS_DEBIT"))
            .andRespond(withSuccess(charged(), MediaType.APPLICATION_JSON))

        val charge = client.chargeByInboundAddress(address, request)

        assertThat(charge).isEqualTo(
            HunterCharge(
                id = id,
                phone = "+420123456789",
                active = true,
                outcome = ChargeOutcome.CHARGED,
                balanceAfterMinor = 2350,
                notice = Notice.NONE,
            ),
        )
        server.verify()
    }

    @Test
    fun `an already-charged answer is mapped as payable`() {
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andRespond(withSuccess(answer("ALREADY_CHARGED", 1000, "NONE"), MediaType.APPLICATION_JSON))

        assertThat(client.chargeByInboundAddress(address, request)?.payable).isTrue()
    }

    @Test
    fun `a refusal is mapped as not payable`() {
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andRespond(withSuccess(answer("INSUFFICIENT", 50, "NONE"), MediaType.APPLICATION_JSON))

        assertThat(client.chargeByInboundAddress(address, request)?.payable).isFalse()
    }

    @Test
    fun `a notice is carried through so the alert can mention it`() {
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andRespond(withSuccess(answer("CHARGED", 350, "LOW_BALANCE"), MediaType.APPLICATION_JSON))

        assertThat(client.chargeByInboundAddress(address, request)?.notice)
            .isEqualTo(Notice.LOW_BALANCE)
    }

    @Test
    fun `an unknown notice is ignored rather than costing the alert`() {
        // user-account learning a new notice is an additive change. It must not dead-letter every
        // alert until this service catches up, so the message simply goes out without the warning.
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andRespond(withSuccess(answer("CHARGED", 350, "SOME_FUTURE_NOTICE"), MediaType.APPLICATION_JSON))

        val charge = client.chargeByInboundAddress(address, request)

        assertThat(charge?.notice).isEqualTo(Notice.NONE)
        assertThat(charge?.payable).isTrue()
    }

    @Test
    fun `an unknown outcome is thrown rather than guessed`() {
        // The opposite call to the notice above, deliberately. Guessing "charged" sends an SMS
        // nobody paid for; guessing "refused" swallows an alert that was paid for. Failing the
        // push is the only honest answer -- Pub/Sub retries, then dead-letters where it is visible.
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andRespond(withSuccess(answer("SOME_FUTURE_OUTCOME", 350, "NONE"), MediaType.APPLICATION_JSON))

        assertThatThrownBy { client.chargeByInboundAddress(address, request) }
            .isInstanceOf(RestClientException::class.java)
    }

    @Test
    fun `an address that belongs to nobody charges nothing and returns null`() {
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThat(client.chargeByInboundAddress(address, request)).isNull()
        server.verify()
    }

    @Test
    fun `server errors are thrown so the event can be retried`() {
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertThatThrownBy { client.chargeByInboundAddress(address, request) }
            .isInstanceOf(HttpServerErrorException::class.java)
    }

    @Test
    fun `charging by id uses the legacy path, for a dead-letter replay`() {
        server.expect(requestTo("$BASE/$id/charges"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("\$.sourceEventId").value(SOURCE_EVENT))
            .andRespond(withSuccess(charged(), MediaType.APPLICATION_JSON))

        assertThat(client.chargeById(id, request)?.outcome).isEqualTo(ChargeOutcome.CHARGED)
        server.verify()
    }

    @Test
    fun `a hunter id that no longer exists returns null`() {
        server.expect(requestTo("$BASE/$id/charges"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThat(client.chargeById(id, request)).isNull()
    }

    @Test
    fun `no Authorization header when there is no UserAccountAuth - the local default`() {
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andRespond(withSuccess(charged(), MediaType.APPLICATION_JSON))

        client.chargeByInboundAddress(address, request)

        server.verify()
    }

    @Test
    fun `sends the credential from UserAccountAuth when one is configured`() {
        // In the cloud this is the Google ID token that gets us past Cloud Run's IAM check on
        // user-account. Without it every charge 403s and the alert is dead-lettered.
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val authorized = HunterClient(builder, "http://user-account") { headers ->
            headers.setBearerAuth("fake-id-token")
        }
        server.expect(requestTo("$BASE/by-inbound-address/charges?address=$address"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fake-id-token"))
            .andRespond(withSuccess(charged(), MediaType.APPLICATION_JSON))

        authorized.chargeByInboundAddress(address, request)

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

        assertThatThrownBy { failing.chargeByInboundAddress(address, request) }
            .isInstanceOf(RestClientException::class.java)
    }

    private fun charged() = answer("CHARGED", 2350, "NONE")

    private fun answer(outcome: String, balance: Long, notice: String) =
        """
        {"hunterId":"$id","phone":"+420123456789","active":true,
         "outcome":"$outcome","balanceAfterMinor":$balance,"notice":"$notice"}
        """.trimIndent()

    private companion object {
        const val BASE = "http://user-account/api/hunters"
        const val SOURCE_EVENT = "11111111-1111-1111-1111-111111111111"
    }
}
