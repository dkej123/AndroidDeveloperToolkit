package dev.acme.adbtoolbox.domain.wifi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** [WifiHost.parse] validates syntax only (hostname or IPv4 literal), per task 039. */
class WifiHostTest {

    @Test
    fun `an IPv4 literal is valid`() {
        val result = WifiHost.parse("192.168.1.42")

        result.shouldBeInstanceOf<WifiHostResult.Valid>()
        (result as WifiHostResult.Valid).host.value shouldBe "192.168.1.42"
    }

    @Test
    fun `a hostname is valid and lowercased`() {
        val result = WifiHost.parse("MyPhone.Local")

        result.shouldBeInstanceOf<WifiHostResult.Valid>()
        (result as WifiHostResult.Valid).host.value shouldBe "myphone.local"
    }

    @Test
    fun `surrounding whitespace is trimmed before validation`() {
        val result = WifiHost.parse("  192.168.1.42  ")

        result.shouldBeInstanceOf<WifiHostResult.Valid>()
        (result as WifiHostResult.Valid).host.value shouldBe "192.168.1.42"
    }

    @Test
    fun `a blank host is invalid`() {
        val result = WifiHost.parse("   ")

        result.shouldBeInstanceOf<WifiHostResult.Invalid>()
    }

    @Test
    fun `a host with embedded whitespace is invalid`() {
        val result = WifiHost.parse("192.168.1. 42")

        result.shouldBeInstanceOf<WifiHostResult.Invalid>()
    }

    @Test
    fun `an IPv4 literal with an out-of-range octet is invalid`() {
        val result = WifiHost.parse("999.0.4.117")

        result.shouldBeInstanceOf<WifiHostResult.Invalid>()
    }

    @Test
    fun `a hostname label with invalid characters is invalid`() {
        val result = WifiHost.parse("my_phone!.local")

        result.shouldBeInstanceOf<WifiHostResult.Invalid>()
    }

    @Test
    fun `a hostname label starting with a hyphen is invalid`() {
        val result = WifiHost.parse("-phone.local")

        result.shouldBeInstanceOf<WifiHostResult.Invalid>()
    }
}
