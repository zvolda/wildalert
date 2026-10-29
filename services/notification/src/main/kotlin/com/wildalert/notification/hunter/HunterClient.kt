package com.wildalert.notification.hunter

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriBuilder
import java.net.URI
import java.util.UUID

/**
 * Resolves a hunter **and charges them for this alert** in one call to user-account.
 *
 * It used to be two concerns and two endpoints (`GET` the phone number, then decide). It is one
 * now because the answer we need is a single question — *may this hunter be texted, and where?* —
 * and splitting it would double the cold-start exposure on a scaled-to-zero user-account and
 * create a looked-up-but-not-charged state. The money and the permission are decided inside the
 * same transaction, which is the only race-free place for it.
 *
 * A 404 (the address belongs to nobody, or the hunter was deleted since the photo arrived) maps to
 * null and charges nothing. **Any other failure is thrown on purpose** — user-account down, a 403
 * from a missing credential, a read timeout on a cold start — so the push returns 5xx and Pub/Sub
 * redelivers instead of silently dropping the hunter's alert. Nothing is charged on that path
 * either: the charge and the debit are the same transaction on the far side, so a call that does
 * not return successfully has taken no money.
 *
 * [auth] is absent locally (user-account is open on localhost) and supplied in the cloud, where
 * user-account requires authentication. A missing credential is **not** a visible error: Cloud Run
 * answers 403, which is not the `NotFound` caught below, so the alert is retried and then
 * dead-lettered. See [UserAccountAuth].
 */
@Component
class HunterClient(
    builder: RestClient.Builder,
    @Value("\${services.user-account.base-url}") baseUrl: String,
    private val auth: UserAccountAuth? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = builder.baseUrl(baseUrl).build()

    /**
     * The normal path: the event carries the recipient address, which is the only identity the
     * pipeline has since recipient resolution moved onto the event.
     */
    fun chargeByInboundAddress(address: String, request: ChargeRequest): HunterCharge? =
        post(request) { builder ->
            builder.path("/api/hunters/by-inbound-address/charges").queryParam("address", address).build()
        } ?: run {
            log.debug("No hunter at inbound address {}", address)
            null
        }

    /**
     * The fallback path, for an event carrying only the legacy `hunterId` — one published before
     * the recipient moved onto the event, or replayed from a dead-letter topic days later.
     */
    fun chargeById(id: UUID, request: ChargeRequest): HunterCharge? =
        post(request) { builder ->
            builder.path("/api/hunters/{id}/charges").build(id)
        } ?: run {
            log.debug("No hunter with id {}", id)
            null
        }

    private fun post(request: ChargeRequest, uri: (UriBuilder) -> URI): HunterCharge? {
        val body = try {
            restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { headers -> auth?.authorize(headers) }
                .body(request)
                .retrieve()
                .body(ChargeResponse::class.java)
        } catch (ex: HttpClientErrorException.NotFound) {
            return null
        } ?: return null

        return HunterCharge(
            id = body.hunterId,
            phone = body.phone,
            active = body.active,
            outcome = body.outcome,
            balanceAfterMinor = body.balanceAfterMinor,
            // Unknown means user-account has learned a notice we have not. Carry on without it
            // rather than failing the alert — see Notice for why this is lenient where the outcome
            // is strict.
            notice = Notice.ofWireValue(body.notice) ?: run {
                log.warn("Ignoring unknown credit notice '{}' from user-account", body.notice)
                Notice.NONE
            },
        )
    }
}
