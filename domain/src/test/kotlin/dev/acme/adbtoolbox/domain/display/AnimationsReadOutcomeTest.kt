package dev.acme.adbtoolbox.domain.display

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimationsReadOutcomeTest {

    @Test
    fun `all three at 0 is AllOff`() {
        val result = combineAnimationReads(
            window = DisplaySettingRead.Value(0f),
            transition = DisplaySettingRead.Value(0f),
            animatorDuration = DisplaySettingRead.Value(0f),
        )

        result shouldBe AnimationsReadOutcome.Ready(AnimationsSummary.AllOff)
    }

    @Test
    fun `all three at 1 is AllOn`() {
        val result = combineAnimationReads(
            window = DisplaySettingRead.Value(1f),
            transition = DisplaySettingRead.Value(1f),
            animatorDuration = DisplaySettingRead.Value(1f),
        )

        result shouldBe AnimationsReadOutcome.Ready(AnimationsSummary.AllOn)
    }

    @Test
    fun `three disagreeing values is Mixed, never flattened into a single boolean`() {
        val result = combineAnimationReads(
            window = DisplaySettingRead.Value(0f),
            transition = DisplaySettingRead.Value(1f),
            animatorDuration = DisplaySettingRead.Value(0.5f),
        )

        result shouldBe AnimationsReadOutcome.Ready(AnimationsSummary.Mixed(window = 0f, transition = 1f, animatorDuration = 0.5f))
    }

    @Test
    fun `three values that agree at a custom scale is Mixed, not coerced to AllOn`() {
        val result = combineAnimationReads(
            window = DisplaySettingRead.Value(0.5f),
            transition = DisplaySettingRead.Value(0.5f),
            animatorDuration = DisplaySettingRead.Value(0.5f),
        )

        result shouldBe AnimationsReadOutcome.Ready(AnimationsSummary.Mixed(window = 0.5f, transition = 0.5f, animatorDuration = 0.5f))
    }

    @Test
    fun `a permission denial on any one setting reports PermissionDenied for the whole read`() {
        val result = combineAnimationReads(
            window = DisplaySettingRead.Value(0f),
            transition = DisplaySettingRead.PermissionDenied("Permission Denial: nope"),
            animatorDuration = DisplaySettingRead.Value(0f),
        )

        result shouldBe AnimationsReadOutcome.PermissionDenied("Permission Denial: nope")
    }

    @Test
    fun `a NotSet setting reports Unsupported naming exactly which settings are unsupported`() {
        val result = combineAnimationReads(
            window = DisplaySettingRead.Value(0f),
            transition = DisplaySettingRead.NotSet,
            animatorDuration = DisplaySettingRead.NotSet,
        )

        result shouldBe AnimationsReadOutcome.Unsupported(
            listOf(AnimationScaleSetting.TRANSITION, AnimationScaleSetting.ANIMATOR_DURATION),
        )
    }

    @Test
    fun `malformed output on any one setting reports Malformed for the whole read`() {
        val result = combineAnimationReads(
            window = DisplaySettingRead.Malformed(raw = "garbage", reason = "expected a decimal scale value or 'null'"),
            transition = DisplaySettingRead.Value(0f),
            animatorDuration = DisplaySettingRead.Value(0f),
        )

        result shouldBe AnimationsReadOutcome.Malformed("garbage")
    }

    @Test
    fun `a transport failure on any one setting is surfaced, never silently dropped`() {
        val outcome = dev.acme.adbtoolbox.domain.adb.AdbOutcome.TimedOut
        val result = combineAnimationReads(
            window = DisplaySettingRead.Value(0f),
            transition = DisplaySettingRead.Value(0f),
            animatorDuration = DisplaySettingRead.TransportFailed(outcome),
        )

        result shouldBe AnimationsReadOutcome.TransportFailed(outcome)
    }
}
