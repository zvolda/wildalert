package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.event.EventPublisher
import com.wildalert.emailingestion.event.ImageReceived
import com.wildalert.emailingestion.storage.ImageStore
import org.springframework.beans.factory.annotation.Value
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
    private val eventPublisher: EventPublisher,
    @Value("\${inbound.email-domain}") private val inboundDomain: String,
) {

    /**
     * Receives a raw trail-cam email (RFC-822 bytes), works out which address it was sent to, then
     * for each image attachment stores it and publishes an ImageReceived event. Returns a summary.
     *
     * The hunter is identified by their personal inbound address, never by `From`: forwarding rules
     * keep the camera's address there, and it is trivially spoofed. [envelopeTo] is the SMTP
     * envelope recipient the email-routing webhook passes along (the most reliable source); after
     * it, the email's own recipient headers are tried.
     *
     * **This service no longer resolves the address to a hunter.** It used to, with a synchronous
     * call to user-account, and a scaled-to-zero user-account then timed out and the photo was
     * published as "no hunter" — a 200 with no alert and nothing in the response to say why. The
     * recipient now travels on the event and notification resolves it, where a cold start costs a
     * Pub/Sub redelivery instead of the alert. Picking our own address out of the recipients needs
     * no lookup, only the inbound domain.
     *
     * An email on no address of ours is fine: the recipient is null and nothing downstream texts
     * anybody.
     */
    @PostMapping(consumes = [MediaType.ALL_VALUE])
    fun receive(
        @RequestBody raw: ByteArray,
        @RequestParam(required = false) envelopeTo: String?,
    ): EmailSummary {
        val email = parser.parse(raw)
        val candidates = (listOfNotNull(envelopeTo?.trim()?.takeIf { it.isNotEmpty() }) + email.recipients)
            .distinctBy { it.lowercase() }
        val recipient = candidates.firstOrNull { it.substringAfterLast('@').equals(inboundDomain, ignoreCase = true) }
        val images = email.images.map { image ->
            val stored = imageStore.store(image.bytes, image.contentType, image.filename)
            eventPublisher.publish(
                ImageReceived(
                    storageKey = stored.key,
                    contentType = image.contentType,
                    senderEmail = email.from,
                    recipient = recipient,
                ),
            )
            ImageInfo(image.filename, image.contentType, image.bytes.size, stored.key)
        }
        return EmailSummary(
            from = email.from,
            subject = email.subject,
            matchedRecipient = recipient,
            imageCount = images.size,
            images = images,
        )
    }
}
