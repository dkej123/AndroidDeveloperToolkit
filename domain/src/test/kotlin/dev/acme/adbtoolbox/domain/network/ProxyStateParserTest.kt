package dev.acme.adbtoolbox.domain.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [parseProxyReadback] turns `settings get global http_proxy` stdout into a [ProxyReadState].
 * Different Android versions/OEMs represent "no proxy" differently, so several disabled forms are
 * covered explicitly rather than assuming one canonical string.
 */
class ProxyStateParserTest {

    @Test
    fun `an empty string is Disabled`() {
        parseProxyReadback("") shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `a blank string is Disabled`() {
        parseProxyReadback("   \n") shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `the reset sentinel colon-zero is Disabled`() {
        parseProxyReadback(":0") shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `the literal string null is Disabled`() {
        parseProxyReadback("null") shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `the literal string null is Disabled regardless of case`() {
        parseProxyReadback("NULL") shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `an active IPv4 endpoint is parsed`() {
        val result = parseProxyReadback("10.0.4.117:8888")

        result.shouldBeInstanceOf<ProxyReadState.Active>()
        val active = result as ProxyReadState.Active
        active.endpoint.host.value shouldBe "10.0.4.117"
        active.endpoint.port.value shouldBe 8888
    }

    @Test
    fun `an active hostname endpoint is parsed`() {
        val result = parseProxyReadback("proxy.acme.dev:3128")

        result.shouldBeInstanceOf<ProxyReadState.Active>()
        val active = result as ProxyReadState.Active
        active.endpoint.host.value shouldBe "proxy.acme.dev"
        active.endpoint.port.value shouldBe 3128
    }

    @Test
    fun `an active IPv6 endpoint is parsed by splitting on the last colon`() {
        val result = parseProxyReadback("2001:db8::1:8080")

        result.shouldBeInstanceOf<ProxyReadState.Active>()
        val active = result as ProxyReadState.Active
        active.endpoint.host.value shouldBe "2001:db8::1"
        active.endpoint.port.value shouldBe 8080
    }

    @Test
    fun `trailing CRLF from the device shell is stripped before parsing`() {
        val result = parseProxyReadback("10.0.4.117:8888\r\n")

        result.shouldBeInstanceOf<ProxyReadState.Active>()
    }

    @Test
    fun `a lone CR line ending is stripped before parsing`() {
        val result = parseProxyReadback("10.0.4.117:8888\r")

        result.shouldBeInstanceOf<ProxyReadState.Active>()
    }

    @Test
    fun `an endpoint with an out-of-range port is Unrecognized, not Active`() {
        val result = parseProxyReadback("10.0.4.117:99999")

        result.shouldBeInstanceOf<ProxyReadState.Unrecognized>()
    }

    @Test
    fun `an endpoint with a non-numeric port is Unrecognized`() {
        val result = parseProxyReadback("10.0.4.117:abc")

        result.shouldBeInstanceOf<ProxyReadState.Unrecognized>()
    }

    @Test
    fun `text with no colon separator is Unrecognized`() {
        result@ run {
            val result = parseProxyReadback("garbage-output")
            result.shouldBeInstanceOf<ProxyReadState.Unrecognized>()
        }
    }

    @Test
    fun `an OEM permission-denied message is Unrecognized, never crashing the parser`() {
        val result = parseProxyReadback("Security exception: Permission denied to read setting")

        result.shouldBeInstanceOf<ProxyReadState.Unrecognized>()
        (result as ProxyReadState.Unrecognized).raw shouldBe "Security exception: Permission denied to read setting"
    }

    @Test
    fun `a value ending in a bare colon with no port is Unrecognized`() {
        val result = parseProxyReadback("10.0.4.117:")

        result.shouldBeInstanceOf<ProxyReadState.Unrecognized>()
    }

    @Test
    fun `the Unrecognized raw text preserves the original untrimmed output`() {
        val raw = "  weird-oem-output  "
        val result = parseProxyReadback(raw)

        result.shouldBeInstanceOf<ProxyReadState.Unrecognized>()
        (result as ProxyReadState.Unrecognized).raw shouldBe raw
    }
}
