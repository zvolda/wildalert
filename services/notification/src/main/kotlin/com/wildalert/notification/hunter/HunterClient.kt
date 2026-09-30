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
 * so the Kafka listener retries the event instead of silently dropping the hunter's alert.
 */
@Component
class HunterClient(
    builder: RestClient.Builder,
    @Value("\${services.user-account.base-url}") baseUrl: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = builder.baseUrl(baseUrl).build()

    fun findById(id: UUID): HunterContact? =
        try {
            restClient.get()
                .uri("/api/hunters/{id}", id)
                .retrieve()
                .body(HunterContact::class.java)
        } catch (ex: HttpClientErrorException.NotFound) {
            log.debug("No hunter with id {}", id)
            null
        }
}
