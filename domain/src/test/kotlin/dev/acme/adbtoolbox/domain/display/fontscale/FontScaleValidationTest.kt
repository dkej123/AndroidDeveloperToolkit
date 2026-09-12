package dev.acme.adbtoolbox.domain.display.fontscale

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class FontScaleValidationTest {

    @Test
    fun `every approved preset validates as Valid`() {
        FontScalePresets.VALUES.forEach { preset ->
            validateFontScale(preset) shouldBe FontScaleValidationResult.Valid(preset)
        }
    }

    @Test
    fun `the minimum of the custom range is valid`() {
        validateFontScale(FontScaleRange.MIN) shouldBe FontScaleValidationResult.Valid(FontScaleRange.MIN)
    }

    @Test
    fun `the maximum of the custom range is valid`() {
        validateFontScale(FontScaleRange.MAX) shouldBe FontScaleValidationResult.Valid(FontScaleRange.MAX)
    }

    @Test
    fun `just below the minimum is out of range`() {
        val result = validateFontScale(0.24)

        result.shouldBeInstanceOf<FontScaleValidationResult.OutOfRange>()
        result shouldBe FontScaleValidationResult.OutOfRange(0.24, FontScaleRange.MIN, FontScaleRange.MAX)
    }

    @Test
    fun `just above the maximum is out of range`() {
        validateFontScale(5.01) shouldBe FontScaleValidationResult.OutOfRange(5.01, FontScaleRange.MIN, FontScaleRange.MAX)
    }

    @Test
    fun `a wildly out of range value is rejected`() {
        validateFontScale(-1.0).shouldBeInstanceOf<FontScaleValidationResult.OutOfRange>()
        validateFontScale(100.0).shouldBeInstanceOf<FontScaleValidationResult.OutOfRange>()
    }
}
