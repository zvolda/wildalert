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
 * Calls the user-account service to match an email sender to a hunter. Uses Spring's
 * RestClient (synchronous). An unknown sender is a normal outcome, not an error, so a
 * 404 from user-account maps to null rather than throwing.
 */
@Component
class HunterLookupClient(
    builder: RestClient.Builder,
    @Value("\${services.user-account.base-url}") baseUrl: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = builder.baseUrl(baseUrl).build()

    /** Returns the matching hunter, or null if no hunter is registered with that email. */
    fun findByEmail(email: String): HunterRef? =
        try {
            restClient.get()
                .uri { uri -> uri.path("/api/hunters/by-email").queryParam("email", email).build() }
                .retrieve()
                .body(HunterRef::class.java)
        } catch (ex: HttpClientErrorException.NotFound) {
            log.debug("No hunter registered for sender {}", email)
            null
        }
}
