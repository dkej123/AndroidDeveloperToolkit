package dev.acme.adbtoolbox.domain.wifi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [WifiEndpoint.parse] splits `host:port` on the last colon and validates each half, per task 039's
 * `adb pair <host:port> <code>` / `adb connect <host:port>` command shapes.
 */
class WifiEndpointTest {

    @Test
    fun `a valid host-colon-port address parses`() {
        val result = WifiEndpoint.parse("192.168.1.42:41000")

        result.shouldBeInstanceOf<WifiEndpointResult.Valid>()
        val endpoint = (result as WifiEndpointResult.Valid).endpoint
        endpoint.host.value shouldBe "192.168.1.42"
        endpoint.port.value shouldBe 41000
    }

    @Test
    fun `render reproduces the exact host colon port form`() {
        val endpoint = (WifiEndpoint.parse("192.168.1.42:41000") as WifiEndpointResult.Valid).endpoint

        endpoint.render() shouldBe "192.168.1.42:41000"
    }

    @Test
    fun `whitespace around the whole address is trimmed`() {
        val result = WifiEndpoint.parse("  192.168.1.42:41000  ")

        result.shouldBeInstanceOf<WifiEndpointResult.Valid>()
    }

    @Test
    fun `a missing colon is invalid`() {
        val result = WifiEndpoint.parse("192.168.1.42")

        result.shouldBeInstanceOf<WifiEndpointResult.Invalid>()
    }

    @Test
    fun `a missing port after the colon is invalid`() {
        val result = WifiEndpoint.parse("192.168.1.42:")

        result.shouldBeInstanceOf<WifiEndpointResult.Invalid>()
    }

    @Test
    fun `a missing host before the colon is invalid`() {
        val result = WifiEndpoint.parse(":41000")

        result.shouldBeInstanceOf<WifiEndpointResult.Invalid>()
    }

    @Test
    fun `an out-of-range port is invalid`() {
        val result = WifiEndpoint.parse("192.168.1.42:70000")

        result.shouldBeInstanceOf<WifiEndpointResult.Invalid>()
    }

    @Test
    fun `an invalid host is invalid`() {
        val result = WifiEndpoint.parse("999.0.4.117:41000")

        result.shouldBeInstanceOf<WifiEndpointResult.Invalid>()
    }

    @Test
    fun `an empty address is invalid`() {
        val result = WifiEndpoint.parse("")

        result.shouldBeInstanceOf<WifiEndpointResult.Invalid>()
    }
}
