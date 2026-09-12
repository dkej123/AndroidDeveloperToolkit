package dev.acme.adbtoolbox.domain.display.fontscale

import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FontScaleFormatTest {

    private lateinit var originalLocale: Locale

    @BeforeEach
    fun captureLocale() {
        originalLocale = Locale.getDefault()
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `formats a preset value with a dot decimal separator`() {
        formatFontScale(1.15) shouldBe "1.15"
    }

    @Test
    fun `formatting is unaffected by a comma-decimal default locale`() {
        Locale.setDefault(Locale.GERMANY)

        formatFontScale(1.15) shouldBe "1.15"
        formatFontScale(1.3) shouldBe "1.3"
    }

    @Test
    fun `formatting is unaffected by a comma-decimal French default locale`() {
        Locale.setDefault(Locale.FRANCE)

        formatFontScale(0.85) shouldBe "0.85"
    }

    @Test
    fun `parses well-formed device stdout into a value regardless of default locale`() {
        Locale.setDefault(Locale.GERMANY)

        parseFontScale("1.15\n") shouldBe FontScaleReadResult.Value(1.15)
    }

    @Test
    fun `parses a bare device value with no trailing newline`() {
        parseFontScale("1.3") shouldBe FontScaleReadResult.Value(1.3)
    }

    @Test
    fun `treats device output of literal null as the platform default`() {
        parseFontScale("null") shouldBe FontScaleReadResult.Value(FontScalePresets.DEFAULT)
    }

    @Test
    fun `treats empty device output as the platform default`() {
        parseFontScale("") shouldBe FontScaleReadResult.Value(FontScalePresets.DEFAULT)
    }

    @Test
    fun `treats blank device output as the platform default`() {
        parseFontScale("   \n").shouldBe(FontScaleReadResult.Value(FontScalePresets.DEFAULT))
    }

    @Test
    fun `reports malformed output instead of throwing`() {
        parseFontScale("Security exception: Permission Denial") shouldBe
            FontScaleReadResult.Malformed("Security exception: Permission Denial")
    }

    @Test
    fun `a comma-decimal locale never causes device output to be misparsed`() {
        Locale.setDefault(Locale.GERMANY)

        // A device would never emit "1,15" (Android's settings provider always uses '.'), but this
        // proves the parser rejects it as malformed rather than silently accepting a comma via a
        // locale-sensitive number parser.
        parseFontScale("1,15") shouldBe FontScaleReadResult.Malformed("1,15")
    }
}
