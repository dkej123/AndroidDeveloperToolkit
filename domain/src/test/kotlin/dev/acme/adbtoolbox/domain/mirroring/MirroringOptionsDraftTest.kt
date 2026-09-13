package dev.acme.adbtoolbox.domain.mirroring

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class MirroringOptionsDraftTest {

    @Test
    fun `the default draft validates to the default options`() {
        val result = validateMirroringOptions(MirroringOptionsDraft.DEFAULT)

        result shouldBe MirroringOptionsValidationResult.Valid(MirroringOptions.DEFAULT)
    }

    @Test
    fun `every field combines into one validated MirroringOptions`() {
        val draft = MirroringOptionsDraft(
            stayAwake = true,
            showTouches = true,
            maxSize = 1920,
            videoBitRateMbps = 8,
        )

        val result = validateMirroringOptions(draft)

        result shouldBe MirroringOptionsValidationResult.Valid(
            MirroringOptions(stayAwake = true, showTouches = true, maxSize = 1920, videoBitRateMbps = 8),
        )
    }

    @Test
    fun `a null maxSize or bit rate is always valid regardless of bounds`() {
        val draft = MirroringOptionsDraft(maxSize = null, videoBitRateMbps = null)

        validateMirroringOptions(draft) shouldBe MirroringOptionsValidationResult.Valid(MirroringOptions.DEFAULT)
    }

    @Test
    fun `a non-positive max size is rejected without constructing MirroringOptions`() {
        val result = validateMirroringOptions(MirroringOptionsDraft(maxSize = 0))

        result shouldBe MirroringOptionsValidationResult.Invalid(setOf(MirroringOptionFieldError.MaxSizeOutOfRange))
    }

    @Test
    fun `a max size above the upper bound is rejected`() {
        val result = validateMirroringOptions(
            MirroringOptionsDraft(maxSize = MirroringOptions.MAX_MAX_SIZE_PX + 1),
        )

        result shouldBe MirroringOptionsValidationResult.Invalid(setOf(MirroringOptionFieldError.MaxSizeOutOfRange))
    }

    @Test
    fun `a max size at the exact bounds is valid`() {
        validateMirroringOptions(MirroringOptionsDraft(maxSize = MirroringOptions.MIN_MAX_SIZE_PX))
            .shouldBeInstanceOf<MirroringOptionsValidationResult.Valid>()
        validateMirroringOptions(MirroringOptionsDraft(maxSize = MirroringOptions.MAX_MAX_SIZE_PX))
            .shouldBeInstanceOf<MirroringOptionsValidationResult.Valid>()
    }

    @Test
    fun `a non-positive video bit rate is rejected without constructing MirroringOptions`() {
        val result = validateMirroringOptions(MirroringOptionsDraft(videoBitRateMbps = -1))

        result shouldBe MirroringOptionsValidationResult.Invalid(setOf(MirroringOptionFieldError.VideoBitRateOutOfRange))
    }

    @Test
    fun `a video bit rate above the upper bound is rejected`() {
        val result = validateMirroringOptions(
            MirroringOptionsDraft(videoBitRateMbps = MirroringOptions.MAX_VIDEO_BIT_RATE_MBPS + 1),
        )

        result shouldBe MirroringOptionsValidationResult.Invalid(setOf(MirroringOptionFieldError.VideoBitRateOutOfRange))
    }

    @Test
    fun `both fields out of range are reported together, not just the first`() {
        val result = validateMirroringOptions(
            MirroringOptionsDraft(maxSize = -1, videoBitRateMbps = -1),
        )

        result shouldBe MirroringOptionsValidationResult.Invalid(
            setOf(MirroringOptionFieldError.MaxSizeOutOfRange, MirroringOptionFieldError.VideoBitRateOutOfRange),
        )
    }

    @Test
    fun `toDraft and DEFAULT round trip both boolean flags and numeric fields`() {
        val options = MirroringOptions(stayAwake = true, showTouches = false, maxSize = 1280, videoBitRateMbps = 4)

        options.toDraft() shouldBe MirroringOptionsDraft(
            stayAwake = true,
            showTouches = false,
            maxSize = 1280,
            videoBitRateMbps = 4,
        )
    }
}
