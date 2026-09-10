package com.wildalert.emailingestion.email

import com.wildalert.emailingestion.client.HunterLookupClient
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
) {

    /**
     * Receives a raw forwarded email (RFC-822 bytes), stores each image attachment, matches the
     * sender to a hunter, and reports what we extracted. A later slice will publish an event.
     * Unknown senders are fine: the response just carries a null matchedHunterId.
     */
    @PostMapping(consumes = [MediaType.ALL_VALUE])
    fun receive(@RequestBody raw: ByteArray): EmailSummary {
        val email = parser.parse(raw)
        val images = email.images.map { image ->
            val stored = imageStore.store(image.bytes, image.contentType, image.filename)
            ImageInfo(image.filename, image.contentType, image.bytes.size, stored.key)
        }
        val matchedHunterId = email.from?.let { hunterLookup.findByEmail(it)?.id }
        return EmailSummary(
            from = email.from,
            subject = email.subject,
            matchedHunterId = matchedHunterId,
            imageCount = images.size,
            images = images,
        )
    }
}
