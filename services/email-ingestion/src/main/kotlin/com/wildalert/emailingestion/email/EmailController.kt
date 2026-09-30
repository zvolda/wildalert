package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.client.HunterLookupClient
import com.wildalert.emailingestion.event.EventPublisher
import com.wildalert.emailingestion.event.ImageReceived
import com.wildalert.emailingestion.storage.ImageStore
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
     * Receives a raw trail-cam email (RFC-822 bytes), matches it to a hunter by recipient, then for
     * each image attachment stores it and publishes an ImageReceived event. Returns a summary.
     *
     * The hunter is identified by their personal inbound address, never by `From`: forwarding rules
     * keep the camera's address there, and it is trivially spoofed. [envelopeTo] is the SMTP
     * envelope recipient the email-routing webhook passes along (the most reliable source); after
     * it, the email's own recipient headers are tried. Unmatched emails are fine: the response and
     * events just carry a null hunter id.
     */
    @PostMapping(consumes = [MediaType.ALL_VALUE])
    fun receive(
        @RequestBody raw: ByteArray,
        @RequestParam(required = false) envelopeTo: String?,
    ): EmailSummary {
        val email = parser.parse(raw)
        val candidates = (listOfNotNull(envelopeTo?.trim()?.takeIf { it.isNotEmpty() }) + email.recipients)
            .distinctBy { it.lowercase() }
        val match = candidates.firstNotNullOfOrNull { address ->
            hunterLookup.findByInboundAddress(address)?.let { hunter -> address to hunter.id }
        }
        val matchedHunterId = match?.second
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
            matchedRecipient = match?.first,
            matchedHunterId = matchedHunterId,
            imageCount = images.size,
            images = images,
        )
    }
}
