package com.wildalert.emailingestion.event

import com.google.api.gax.core.CredentialsProvider
import com.google.api.gax.core.NoCredentialsProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Lets the Pub/Sub emulator work with no Google credentials at all. **Do not delete this as
 * unused** — nothing in our code calls it; Spring Cloud GCP does.
 *
 * Why it is needed: `GcpPubSubAutoConfiguration` does switch to [NoCredentialsProvider] when an
 * emulator host is set, but it takes [CredentialsProvider] as a *constructor parameter*, so Spring
 * must first build `GcpContextAutoConfiguration`'s eager `DefaultCredentialsProvider` bean — which
 * calls `GoogleCredentials.getApplicationDefault()` and throws when there are none. Declaring our
 * own [CredentialsProvider] wins, because that auto-configured bean is `@ConditionalOnMissingBean`.
 * (GoogleCloudPlatform/spring-cloud-gcp#3560)
 *
 * Only active when `spring.cloud.gcp.pubsub.emulator-host` is set — which is why that property is
 * never written into application.yml with an empty default: present-but-empty would match here and
 * silently strip credentials in the cloud. Local dev sets
 * `SPRING_CLOUD_GCP_PUBSUB_EMULATOR_HOST` as an environment variable instead (see .env.example).
 */
@Configuration
@ConditionalOnProperty(name = ["spring.cloud.gcp.pubsub.emulator-host"])
class PubSubCredentialsConfig {

    @Bean
    fun emulatorCredentialsProvider(): CredentialsProvider = NoCredentialsProvider.create()
}
