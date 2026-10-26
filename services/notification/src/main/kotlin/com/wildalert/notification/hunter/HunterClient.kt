package com.wildalert.notification.hunter

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.util.UUID

/** The bits of a hunter needed to text them. */
data class HunterContact(
    val id: UUID,
    val phone: String,
    val active: Boolean,
)

/**
 * Calls the user-account service to get a hunter's phone number. A 404 (hunter deleted since the
 * photo arrived) maps to null. Any other failure — e.g. user-account down — is thrown on purpose,
 * so the push returns 5xx and Pub/Sub redelivers, instead of silently dropping the hunter's alert.
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

    fun findById(id: UUID): HunterContact? =
        try {
            restClient.get()
                .uri("/api/hunters/{id}", id)
                .headers { headers -> auth?.authorize(headers) }
                .retrieve()
                .body(HunterContact::class.java)
        } catch (ex: HttpClientErrorException.NotFound) {
            log.debug("No hunter with id {}", id)
            null
        }

    /**
     * Resolves the address an alert was addressed to. A 404 means the address is not a hunter's —
     * mail to a made-up name on our domain, which email-ingestion deliberately does not filter —
     * and maps to null. Everything else is thrown, so the event is redelivered rather than dropped.
     */
    fun findByInboundAddress(address: String): HunterContact? =
        try {
            restClient.get()
                .uri { builder -> builder.path("/api/hunters/by-inbound-address").queryParam("address", address).build() }
                .headers { headers -> auth?.authorize(headers) }
                .retrieve()
                .body(HunterContact::class.java)
        } catch (ex: HttpClientErrorException.NotFound) {
            log.debug("No hunter at inbound address {}", address)
            null
        }
}
