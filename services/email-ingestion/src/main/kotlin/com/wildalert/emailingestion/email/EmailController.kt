package com.wildalert.emailingestion.email

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/emails")
class EmailController(
    private val parser: EmailParser,
) {

    /**
     * Receives a raw forwarded email (RFC-822 bytes) and reports what we extracted.
     * Later slices will store the image and publish an event instead of just summarising.
     */
    @PostMapping(consumes = [MediaType.ALL_VALUE])
    fun receive(@RequestBody raw: ByteArray): EmailSummary {
        val email = parser.parse(raw)
        return EmailSummary(
            from = email.from,
            subject = email.subject,
            imageCount = email.images.size,
            images = email.images.map { ImageInfo(it.filename, it.contentType, it.bytes.size) },
        )
    }
}
