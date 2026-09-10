package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.client.HunterLookupClient
import com.wildalert.emailingestion.event.EventPublisher
import com.wildalert.emailingestion.event.ImageReceived
import com.wildalert.emailingestion.storage.ImageStore
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/emails")
class EmailController(
    private val parser: EmailParser,
    private val imageStore: ImageStore,
    private val hunterLookup: HunterLookupClient,
    private val eventPublisher: EventPublisher,
) {

    /**
     * Receives a raw forwarded email (RFC-822 bytes), matches the sender to a hunter, then for
     * each image attachment stores it and publishes an ImageReceived event. Returns a summary.
     * Unknown senders are fine: the response and events just carry a null hunter id.
     */
    @PostMapping(consumes = [MediaType.ALL_VALUE])
    fun receive(@RequestBody raw: ByteArray): EmailSummary {
        val email = parser.parse(raw)
        val matchedHunterId = email.from?.let { hunterLookup.findByEmail(it)?.id }
        val images = email.images.map { image ->
            val stored = imageStore.store(image.bytes, image.contentType, image.filename)
            eventPublisher.publish(
                ImageReceived(
                    storageKey = stored.key,
                    contentType = image.contentType,
                    senderEmail = email.from,
                    hunterId = matchedHunterId,
                ),
            )
            ImageInfo(image.filename, image.contentType, image.bytes.size, stored.key)
        }
        return EmailSummary(
            from = email.from,
            subject = email.subject,
            matchedHunterId = matchedHunterId,
            imageCount = images.size,
            images = images,
        )
    }
}
