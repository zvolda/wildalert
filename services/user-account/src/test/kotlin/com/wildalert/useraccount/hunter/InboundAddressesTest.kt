package com.wildalert.useraccount.hunter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils

class InboundAddressesTest {

    private val addresses = InboundAddresses(" In.WildAlert.test ")

    @Test
    fun `new tokens are 12 unambiguous lowercase characters and do not repeat`() {
        val tokens = (1..1000).map { InboundAddresses.newToken() }

        assertThat(tokens).allMatch { it.matches(Regex("^[a-km-np-z2-9]{12}$")) }
        assertThat(tokens.toSet()).hasSize(1000)
    }

    @Test
    fun `address is the hunter's token at the configured domain`() {
        val hunter = Hunter("hunter@example.com", "+420123456789", Plan.FREE)
        ReflectionTestUtils.setField(hunter, "inboundToken", "7f3k9qabcdef")

        assertThat(addresses.addressFor(hunter)).isEqualTo("7f3k9qabcdef@in.wildalert.test")
    }

    @Test
    fun `token is read back from an address regardless of case and whitespace`() {
        assertThat(addresses.tokenOf("  7F3K9QABCDEF@In.WildAlert.TEST ")).isEqualTo("7f3k9qabcdef")
    }

    @Test
    fun `addresses on other domains are not ours`() {
        assertThat(addresses.tokenOf("7f3k9qabcdef@gmail.com")).isNull()
        assertThat(addresses.tokenOf("7f3k9qabcdef@evil-in.wildalert.test")).isNull()
    }

    @Test
    fun `malformed addresses are rejected`() {
        assertThat(addresses.tokenOf("in.wildalert.test")).isNull()
        assertThat(addresses.tokenOf("@in.wildalert.test")).isNull()
        assertThat(addresses.tokenOf("jan.novak@in.wildalert.test")).isNull()
    }
}
