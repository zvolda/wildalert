package com.wildalert.emailingestion.email

/** Metadata about one extracted image, plus the object key it was stored under. */
data class ImageInfo(
    val filename: String?,
    val contentType: String?,
    val sizeBytes: Int,
    val storageKey: String,
)

/** What the webhook returns after parsing an email. */
data class EmailSummary(
    val from: String?,
    val subject: String?,
    // The address on our inbound domain that this email was sent to, or null if it was sent to
    // none of ours. Which hunter that belongs to is notification's business, not ours — so there
    // is deliberately no hunter id here any more.
    val matchedRecipient: String?,
    val imageCount: Int,
    val images: List<ImageInfo>,
)
