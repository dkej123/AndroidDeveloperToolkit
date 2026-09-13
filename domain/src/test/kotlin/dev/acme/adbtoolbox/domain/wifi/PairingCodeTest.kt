package dev.acme.adbtoolbox.domain.wifi

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [PairingCode.parse] enforces the exact 6-digit form Android's wireless-debugging pairing dialog
 * shows, and [PairingCode.toString] never leaks the digits (task 039: "codes are never ... logged").
 */
class PairingCodeTest {

    @Test
    fun `a 6-digit code is valid`() {
        val result = PairingCode.parse("123456")

        result.shouldBeInstanceOf<PairingCodeResult.Valid>()
        (result as PairingCodeResult.Valid).code.value shouldBe "123456"
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val result = PairingCode.parse("  123456  ")

        result.shouldBeInstanceOf<PairingCodeResult.Valid>()
    }

    @Test
    fun `a code shorter than 6 digits is invalid`() {
        val result = PairingCode.parse("12345")

        result.shouldBeInstanceOf<PairingCodeResult.Invalid>()
    }

    @Test
    fun `a code longer than 6 digits is invalid`() {
        val result = PairingCode.parse("1234567")

        result.shouldBeInstanceOf<PairingCodeResult.Invalid>()
    }

    @Test
    fun `a code containing non-digit characters is invalid`() {
        val result = PairingCode.parse("12a456")

        result.shouldBeInstanceOf<PairingCodeResult.Invalid>()
    }

    @Test
    fun `an empty code is invalid`() {
        val result = PairingCode.parse("")

        result.shouldBeInstanceOf<PairingCodeResult.Invalid>()
    }

    @Test
    fun `toString never contains the raw digits`() {
        val code = (PairingCode.parse("123456") as PairingCodeResult.Valid).code

        code.toString().shouldNotContain("123456")
    }
}
