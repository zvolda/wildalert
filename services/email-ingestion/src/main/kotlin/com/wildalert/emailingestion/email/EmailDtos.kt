package com.wildalert.emailingestion.email

/** Metadata about one extracted image (no bytes — those get stored later). */
data class ImageInfo(
    val filename: String?,
    val contentType: String?,
    val sizeBytes: Int,
)

/** What the webhook returns after parsing an email. */
data class EmailSummary(
    val from: String?,
    val subject: String?,
    val imageCount: Int,
    val images: List<ImageInfo>,
)
