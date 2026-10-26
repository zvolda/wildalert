package com.wildalert.emailingestion.email

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The inbound domain is a **contract between two services and the compose file**, and getting it
 * wrong fails silently: user-account mints `<token>@<its domain>`, email-ingestion decides which
 * of an email's recipients is ours by matching `<its domain>`, and nothing compares the two at
 * runtime. A mismatch means no recipient is ever matched, every photo publishes with none, the
 * webhook still answers 200, and no hunter is ever texted.
 *
 * That is not hypothetical: email-ingestion's default was `in.wildalert.local` (a leftover from
 * the subdomain design the Cloudflare apex catch-all replaced) while user-account's was
 * `wildalert.local`, and compose passed the variable to user-account only. Every other test in
 * this module still passed, because they all take the domain from the same file they validate.
 * So the invariant is pinned here, against the files themselves.
 */
class InboundDomainContractTest {

    private val repoRoot: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no settings.gradle.kts above ${File("").absolutePath}")
        }
        dir
    }

    private val envVar = "INBOUND_EMAIL_DOMAIN"

    @Test
    fun `email-ingestion and user-account default to the same inbound domain, from the same variable`() {
        val ingestion = placeholderOf("services/email-ingestion", "inbound", "email-domain")
        val account = placeholderOf("services/user-account", "hunters", "inbound-domain")

        // Same variable: in the cloud one value has to configure both services.
        assertThat(ingestion.variable).isEqualTo(envVar)
        assertThat(account.variable).isEqualTo(envVar)

        // Same default: with the variable unset — local dev, and every test that boots a context —
        // the address user-account mints must be one email-ingestion recognizes as ours.
        assertThat(ingestion.default)
            .describedAs("email-ingestion's default inbound domain must equal user-account's")
            .isEqualTo(account.default)
    }

    @Test
    fun `compose passes the inbound domain to both services that read it`() {
        val services = composeServices()

        // Only these two read it; recognition and notification pass the recipient through opaquely.
        for (name in listOf("user-account", "email-ingestion")) {
            val env = environmentOf(services, name)
            assertThat(env)
                .describedAs("compose service '%s' must be given %s", name, envVar)
                .containsKey(envVar)
            assertThat(env.getValue(envVar).toString())
                .describedAs("compose service '%s' must take %s from the environment", name, envVar)
                .startsWith("\${$envVar")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun composeServices(): Map<String, Map<String, Any>> =
        Yaml().load<Map<String, Any>>(File(repoRoot, "compose.yml").readText())
            .getValue("services") as Map<String, Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun environmentOf(services: Map<String, Map<String, Any>>, name: String): Map<String, Any> =
        services.getValue(name).getValue("environment") as Map<String, Any>

    private data class Placeholder(val variable: String, val default: String)

    /** Reads `key: ${VAR:default}` out of a service's `application.yml` without booting Spring. */
    @Suppress("UNCHECKED_CAST")
    private fun placeholderOf(module: String, section: String, key: String): Placeholder {
        val yml = File(repoRoot, "$module/src/main/resources/application.yml")
        val value = (Yaml().load<Map<String, Any>>(yml.readText()).getValue(section) as Map<String, Any>)
            .getValue(key).toString()

        val match = Regex("""^\$\{([A-Z_]+):(.*)}$""").find(value)
            ?: error("$module $section.$key is '$value', not a \${VAR:default} placeholder")
        return Placeholder(match.groupValues[1], match.groupValues[2])
    }
}
