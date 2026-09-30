package com.wildalert.emailingestion.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/** Local-folder storage settings, bound from `storage.filesystem.*`. */
@ConfigurationProperties(prefix = "storage.filesystem")
data class FileSystemProperties(
    // Folder images are written under (as <root>/<key>). The recognition service reads the same
    // folder, so both must point at it (RECOGNITION_IMAGE_ROOT on the Python side).
    val root: String = "data/images",
)
