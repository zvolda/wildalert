package com.wildalert.emailingestion.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/**
 * Real ImageStore backed by Cloudflare R2. R2 speaks the S3 API, so we use Amazon's S3 SDK
 * but point its endpoint at R2 and set region to "auto". Active only when storage.provider=r2,
 * so the fake logging store is used until a bucket and credentials are configured.
 */
@Component
@ConditionalOnProperty(name = ["storage.provider"], havingValue = "r2")
class R2ImageStore(
    private val props: R2Properties,
) : ImageStore {

    private val log = LoggerFactory.getLogger(javaClass)

    private val s3: S3Client = S3Client.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)),
        )
        .build()

    init {
        log.info("R2 image store initialised (bucket={}, endpoint={})", props.bucket, props.endpoint)
    }

    override fun store(bytes: ByteArray, contentType: String?, filename: String?): StoredImage {
        val key = ObjectKeys.newKey(contentType, filename)
        val request = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentType(contentType ?: "application/octet-stream")
            .build()
        s3.putObject(request, RequestBody.fromBytes(bytes))
        log.info("Stored image in R2: key={} size={}", key, bytes.size)
        return StoredImage(key = key, sizeBytes = bytes.size)
    }
}
