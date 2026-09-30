package com.wildalert.emailingestion.client

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.util.UUID

/** The bits of a hunter we need after matching a sender. */
data class HunterRef(
    val id: UUID,
)

/**
 * Calls the user-account service to match an email to a hunter by the hunter's personal inbound
 * address (the email's recipient). Uses Spring's RestClient (synchronous).
 *
 * Two kinds of "no hunter" are deliberately treated the same: an address that isn't a hunter's
 * (404) and a lookup we could not complete at all. Failing the webhook instead would be worse —
 * the caller would get a 500 and the photo would never be stored, losing it for good. Degrading
 * to "no match" keeps the image and still publishes an ImageReceived event (with a null hunterId),
 * which the rest of the pipeline already handles; only the SMS for that one photo is missed.
 *
 * A failed lookup is retried once first, because the common cause is a user-account instance
 * cold-starting rather than a real outage. Timeouts come from `spring.http.client.*`.
 */
@Component
class HunterLookupClient(
    builder: RestClient.Builder,
    @Value("\${services.user-account.base-url}") baseUrl: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = builder.baseUrl(baseUrl).build()

    /** Returns the hunter who owns this inbound address, or null if it isn't a hunter's. */
    fun findByInboundAddress(address: String): HunterRef? {
        repeat(ATTEMPTS) { attempt ->
            try {
                return lookUp(address)
            } catch (ex: HttpClientErrorException.NotFound) {
                log.debug("No hunter owns inbound address {}", address)
                return null
            } catch (ex: RestClientException) {
                val lastAttempt = attempt == ATTEMPTS - 1
                if (lastAttempt) {
                    log.error(
                        "Could not reach user-account to match {} after {} attempts; treating it as " +
                            "no match, so the photo is still stored and published without a hunter",
                        address, ATTEMPTS, ex,
                    )
                } else {
                    log.warn("Lookup of {} failed ({}); retrying once", address, ex.message)
                }
            }
        }
        return null
    }

    private fun lookUp(address: String): HunterRef? =
        restClient.get()
            .uri { uri -> uri.path("/api/hunters/by-inbound-address").queryParam("address", address).build() }
            .retrieve()
            .body(HunterRef::class.java)

    private companion object {
        /** One retry: enough to ride out a cold-starting user-account, bounded enough for a webhook. */
        const val ATTEMPTS = 2
    }
}
