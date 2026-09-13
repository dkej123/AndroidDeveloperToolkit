package dev.acme.adbtoolbox.domain.wifi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** [WifiPort.parse] enforces the 1-65535 range explicitly at both boundaries, per task 039. */
class WifiPortTest {

    @Test
    fun `the lower boundary port 1 is valid`() {
        val result = WifiPort.parse("1")

        result.shouldBeInstanceOf<WifiPortResult.Valid>()
        (result as WifiPortResult.Valid).port.value shouldBe 1
    }

    @Test
    fun `the upper boundary port 65535 is valid`() {
        val result = WifiPort.parse("65535")

        result.shouldBeInstanceOf<WifiPortResult.Valid>()
        (result as WifiPortResult.Valid).port.value shouldBe 65535
    }

    @Test
    fun `port 0 is rejected`() {
        val result = WifiPort.parse("0")

        result.shouldBeInstanceOf<WifiPortResult.Invalid>()
    }

    @Test
    fun `port 65536 is rejected`() {
        val result = WifiPort.parse("65536")

        result.shouldBeInstanceOf<WifiPortResult.Invalid>()
    }

    @Test
    fun `whitespace around a port string is trimmed`() {
        val result = WifiPort.parse(" 41000 ")

        result.shouldBeInstanceOf<WifiPortResult.Valid>()
        (result as WifiPortResult.Valid).port.value shouldBe 41000
    }

    @Test
    fun `a non-numeric port string is rejected`() {
        val result = WifiPort.parse("abc")

        result.shouldBeInstanceOf<WifiPortResult.Invalid>()
    }

    @Test
    fun `an empty port string is rejected`() {
        val result = WifiPort.parse("")

        result.shouldBeInstanceOf<WifiPortResult.Invalid>()
    }

    @Test
    fun `a port string exceeding Int range is rejected, not crashing`() {
        val result = WifiPort.parse("99999999999999999999")

        result.shouldBeInstanceOf<WifiPortResult.Invalid>()
    }
}
