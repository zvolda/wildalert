package com.wildalert.emailingestion.email

import java.util.UUID

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
    // Which registered hunter the sender matched, or null if the sender is unknown.
    val matchedHunterId: UUID?,
    val imageCount: Int,
    val images: List<ImageInfo>,
)
