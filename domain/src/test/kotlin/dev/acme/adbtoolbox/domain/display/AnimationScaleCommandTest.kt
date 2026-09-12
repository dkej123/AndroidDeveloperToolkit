package dev.acme.adbtoolbox.domain.display

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimationScaleCommandTest {

    private val serial = DeviceSerial.of("emulator-5554")

    @Test
    fun `each of the three settings has its own global settings name`() {
        AnimationScaleSetting.WINDOW.settingName shouldBe "window_animation_scale"
        AnimationScaleSetting.TRANSITION.settingName shouldBe "transition_animation_scale"
        AnimationScaleSetting.ANIMATOR_DURATION.settingName shouldBe "animator_duration_scale"
    }

    @Test
    fun `read request targets the exact serial and setting`() {
        val request = AnimationScaleCommand.readRequest(serial, AnimationScaleSetting.TRANSITION)

        request.serial shouldBe serial
        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "settings get global transition_animation_scale"
    }

    @Test
    fun `write request renders 0 for off`() {
        val request = AnimationScaleCommand.writeRequest(serial, AnimationScaleSetting.WINDOW, enabled = false)

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "settings put global window_animation_scale 0"
    }

    @Test
    fun `write request renders 1 for on`() {
        val request = AnimationScaleCommand.writeRequest(serial, AnimationScaleSetting.ANIMATOR_DURATION, enabled = true)

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "settings put global animator_duration_scale 1"
    }

    @Test
    fun `parses a well-formed float`() {
        AnimationScaleCommand.parseRead(textResult("0.5\n")) shouldBe DisplaySettingRead.Value(0.5f)
    }

    @Test
    fun `parses an integral value without a decimal point`() {
        AnimationScaleCommand.parseRead(textResult("0\n")) shouldBe DisplaySettingRead.Value(0f)
    }

    @Test
    fun `parses the settings null sentinel as NotSet`() {
        AnimationScaleCommand.parseRead(textResult("null\n")) shouldBe DisplaySettingRead.NotSet
    }

    @Test
    fun `parses malformed output as Malformed`() {
        val result = AnimationScaleCommand.parseRead(textResult("fast\n"))

        result shouldBe DisplaySettingRead.Malformed(raw = "fast\n", reason = "expected a decimal scale value or 'null'")
    }

    @Test
    fun `parses a permission denial in stderr as PermissionDenied`() {
        val result = AnimationScaleCommand.parseRead(
            AdbTextResult(
                outcome = AdbOutcome.Completed(exitCode = 0),
                stdout = "",
                stderr = "Permission Denial: writing to global settings\n",
            ),
        )

        result shouldBe DisplaySettingRead.PermissionDenied("Permission Denial: writing to global settings\n")
    }

    @Test
    fun `a non-Completed outcome is reported as TransportFailed`() {
        val outcome = AdbOutcome.Cancelled
        AnimationScaleCommand.parseRead(AdbTextResult(outcome = outcome, stdout = "", stderr = "")) shouldBe
            DisplaySettingRead.TransportFailed(outcome)
    }

    private fun textResult(stdout: String) =
        AdbTextResult(outcome = AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = "")
}
