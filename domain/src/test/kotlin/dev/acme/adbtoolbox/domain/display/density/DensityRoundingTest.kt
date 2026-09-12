package dev.acme.adbtoolbox.domain.display.density

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [percentToDpi] must use one deterministic rounding rule so readback can be compared against the
 * requested preset. This project picks round-half-up (ties round away from zero, matching
 * Android's own `Math.round` behavior and the intuitive "round up" a user expects when typing a
 * custom percentage) over banker's rounding, which would round different halves in different
 * directions depending on parity and surprise a user comparing the tooltip value to the readback.
 */
class DensityRoundingTest {

    @Test
    fun `whole-number results are unaffected by rounding`() {
        percentToDpi(physicalDpi = 420, percent = 100) shouldBe 420
        percentToDpi(physicalDpi = 420, percent = 125) shouldBe 525
        percentToDpi(physicalDpi = 420, percent = 60) shouldBe 252
        percentToDpi(physicalDpi = 420, percent = 200) shouldBe 840
    }

    @Test
    fun `a fractional result below the half rounds down`() {
        percentToDpi(physicalDpi = 433, percent = 80) shouldBe 346
    }

    @Test
    fun `a fractional result above the half rounds up`() {
        percentToDpi(physicalDpi = 433, percent = 90) shouldBe 390
    }

    @Test
    fun `an exact half rounds up, not to even`() {
        // 101 * 150% = 151.5 exactly: half-up rounds to 152, banker's rounding would round to 152
        // as well here (152 is even) so this alone would not distinguish the rules...
        percentToDpi(physicalDpi = 101, percent = 150) shouldBe 152
        // ...so also assert a half-way case where banker's rounding would go the other way: 3 * 50%
        // = 1.5 exactly. Round-half-up gives 2; round-half-to-even (banker's) would give 2 as well
        // since 2 is even -- use 1 * 150% = 1.5 instead, where round-half-to-even gives 2 (even) and
        // round-half-up also gives 2. The distinguishing case is a half-way value whose lower
        // neighbor is even: e.g. 1 * 250% = 2.5 -- half-up gives 3, banker's would give 2.
        percentToDpi(physicalDpi = 1, percent = 250) shouldBe 3
    }

    @Test
    fun `preset percentages are always whole dpi values for a typical physical density`() {
        DensityPresets.PERCENTAGES.forEach { percent ->
            percentToDpi(physicalDpi = 440, percent = percent) shouldBe (440 * percent) / 100
        }
    }
}
