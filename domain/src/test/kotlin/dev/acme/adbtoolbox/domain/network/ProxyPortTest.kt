package dev.acme.adbtoolbox.domain.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** [ProxyPort.parse] enforces the 1-65535 range explicitly at both boundaries, per task 030. */
class ProxyPortTest {

    @Test
    fun `the lower boundary port 1 is valid`() {
        val result = ProxyPort.parse(1)

        result.shouldBeInstanceOf<ProxyPortResult.Valid>()
        (result as ProxyPortResult.Valid).port.value shouldBe 1
    }

    @Test
    fun `the upper boundary port 65535 is valid`() {
        val result = ProxyPort.parse(65535)

        result.shouldBeInstanceOf<ProxyPortResult.Valid>()
        (result as ProxyPortResult.Valid).port.value shouldBe 65535
    }

    @Test
    fun `port 0 is rejected`() {
        val result = ProxyPort.parse(0)

        result.shouldBeInstanceOf<ProxyPortResult.Invalid>()
    }

    @Test
    fun `port 65536 is rejected`() {
        val result = ProxyPort.parse(65536)

        result.shouldBeInstanceOf<ProxyPortResult.Invalid>()
    }

    @Test
    fun `a negative port is rejected`() {
        val result = ProxyPort.parse(-1)

        result.shouldBeInstanceOf<ProxyPortResult.Invalid>()
    }

    @Test
    fun `a typical port string parses to Valid`() {
        val result = ProxyPort.parse("8888")

        result.shouldBeInstanceOf<ProxyPortResult.Valid>()
        (result as ProxyPortResult.Valid).port.value shouldBe 8888
    }

    @Test
    fun `whitespace around a port string is trimmed`() {
        val result = ProxyPort.parse(" 8888 ")

        result.shouldBeInstanceOf<ProxyPortResult.Valid>()
    }

    @Test
    fun `a non-numeric port string is rejected`() {
        val result = ProxyPort.parse("abc")

        result.shouldBeInstanceOf<ProxyPortResult.Invalid>()
    }

    @Test
    fun `an empty port string is rejected`() {
        val result = ProxyPort.parse("")

        result.shouldBeInstanceOf<ProxyPortResult.Invalid>()
    }

    @Test
    fun `a port string with a decimal point is rejected`() {
        val result = ProxyPort.parse("80.5")

        result.shouldBeInstanceOf<ProxyPortResult.Invalid>()
    }

    @Test
    fun `a port string exceeding Int range is rejected, not crashing`() {
        val result = ProxyPort.parse("99999999999999999999")

        result.shouldBeInstanceOf<ProxyPortResult.Invalid>()
    }
}
