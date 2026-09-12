package dev.acme.adbtoolbox.domain.display.density

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Safety policy (design/README.md §5: "Values outside 60-200% can make the UI unusable"): a
 * custom absolute-dpi request is validated against a safe range expressed as a percentage of the
 * physical density, computed with the same [percentToDpi] rounding rule the presets use, before
 * any command is issued.
 */
class DensityValidationTest {

    private val physicalDpi = 420

    @Test
    fun `a value at the minimum boundary is accepted`() {
        val minDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MIN_PERCENT)

        validateCustomDensity(physicalDpi, minDpi) shouldBe DensityValidationResult.Valid(minDpi)
    }

    @Test
    fun `a value at the maximum boundary is accepted`() {
        val maxDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MAX_PERCENT)

        validateCustomDensity(physicalDpi, maxDpi) shouldBe DensityValidationResult.Valid(maxDpi)
    }

    @Test
    fun `a value one below the minimum boundary is rejected`() {
        val minDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MIN_PERCENT)
        val maxDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MAX_PERCENT)

        validateCustomDensity(physicalDpi, minDpi - 1) shouldBe
            DensityValidationResult.OutOfRange(minDpi - 1, minDpi, maxDpi)
    }

    @Test
    fun `a value one above the maximum boundary is rejected`() {
        val minDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MIN_PERCENT)
        val maxDpi = percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MAX_PERCENT)

        validateCustomDensity(physicalDpi, maxDpi + 1) shouldBe
            DensityValidationResult.OutOfRange(maxDpi + 1, minDpi, maxDpi)
    }

    @Test
    fun `a value equal to the physical density is accepted`() {
        validateCustomDensity(physicalDpi, physicalDpi) shouldBe DensityValidationResult.Valid(physicalDpi)
    }

    @Test
    fun `a wildly unsafe value is rejected`() {
        validateCustomDensity(physicalDpi, 1) shouldBe DensityValidationResult.OutOfRange(
            1,
            percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MIN_PERCENT),
            percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MAX_PERCENT),
        )
        validateCustomDensity(physicalDpi, 100_000) shouldBe DensityValidationResult.OutOfRange(
            100_000,
            percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MIN_PERCENT),
            percentToDpi(physicalDpi, DensityPresets.SAFE_RANGE_MAX_PERCENT),
        )
    }

    @Test
    fun `a nonpositive requested dpi is always rejected regardless of physical density`() {
        validateCustomDensity(physicalDpi, 0).shouldBeOutOfRange()
        validateCustomDensity(physicalDpi, -10).shouldBeOutOfRange()
    }

    private fun DensityValidationResult.shouldBeOutOfRange() {
        (this is DensityValidationResult.OutOfRange) shouldBe true
    }
}
