package com.wildalert.emailingestion.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/** Cloudflare R2 settings, bound from `storage.r2.*` (fed by env vars / .env). */
@ConfigurationProperties(prefix = "storage.r2")
data class R2Properties(
    // S3 API endpoint for the account, e.g. https://<account-id>.r2.cloudflarestorage.com
    val endpoint: String = "",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    // R2 ignores the region but the S3 SDK requires one; "auto" is Cloudflare's recommendation.
    val region: String = "auto",
)
