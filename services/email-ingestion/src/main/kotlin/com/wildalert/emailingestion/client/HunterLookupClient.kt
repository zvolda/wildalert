package com.wildalert.emailingestion.client

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.util.UUID

/** The bits of a hunter we need after matching a sender. */
data class HunterRef(
    val id: UUID,
)

/**
 * Calls the user-account service to match an email to a hunter by the hunter's personal inbound
 * address (the email's recipient). Uses Spring's RestClient (synchronous). An address that isn't a
 * hunter's is a normal outcome, not an error, so a 404 from user-account maps to null.
 */
@Component
class HunterLookupClient(
    builder: RestClient.Builder,
    @Value("\${services.user-account.base-url}") baseUrl: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = builder.baseUrl(baseUrl).build()

    /** Returns the hunter who owns this inbound address, or null if it isn't a hunter's. */
    fun findByInboundAddress(address: String): HunterRef? =
        try {
            restClient.get()
                .uri { uri -> uri.path("/api/hunters/by-inbound-address").queryParam("address", address).build() }
                .retrieve()
                .body(HunterRef::class.java)
        } catch (ex: HttpClientErrorException.NotFound) {
            log.debug("No hunter owns inbound address {}", address)
            null
        }
}
