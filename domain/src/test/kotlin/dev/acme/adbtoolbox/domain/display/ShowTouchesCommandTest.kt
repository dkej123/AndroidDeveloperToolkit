package dev.acme.adbtoolbox.domain.display

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShowTouchesCommandTest {

    private val serial = DeviceSerial.of("emulator-5554")

    @Test
    fun `read request targets the exact serial with settings get system show_touches`() {
        val request = ShowTouchesCommand.readRequest(serial)

        request.serial shouldBe serial
        (request.operation as AdbOperation.Shell).command.render() shouldBe "settings get system show_touches"
    }

    @Test
    fun `write request renders 1 for enabled`() {
        val request = ShowTouchesCommand.writeRequest(serial, enabled = true)

        (request.operation as AdbOperation.Shell).command.render() shouldBe "settings put system show_touches 1"
    }

    @Test
    fun `write request renders 0 for disabled`() {
        val request = ShowTouchesCommand.writeRequest(serial, enabled = false)

        (request.operation as AdbOperation.Shell).command.render() shouldBe "settings put system show_touches 0"
    }

    @Test
    fun `parses 1 as true`() {
        ShowTouchesCommand.parseRead(textResult("1\n")) shouldBe DisplaySettingRead.Value(true)
    }

    @Test
    fun `parses 0 as false`() {
        ShowTouchesCommand.parseRead(textResult("0\n")) shouldBe DisplaySettingRead.Value(false)
    }

    @Test
    fun `parses the settings null sentinel as NotSet`() {
        ShowTouchesCommand.parseRead(textResult("null\n")) shouldBe DisplaySettingRead.NotSet
    }

    @Test
    fun `parses malformed output as Malformed`() {
        val result = ShowTouchesCommand.parseRead(textResult("maybe\n"))

        result shouldBe DisplaySettingRead.Malformed(raw = "maybe\n", reason = "expected '1', '0', or 'null'")
    }

    @Test
    fun `parses a permission denial in stderr as PermissionDenied`() {
        val result = ShowTouchesCommand.parseRead(
            AdbTextResult(
                outcome = AdbOutcome.Completed(exitCode = 0),
                stdout = "",
                stderr = "Permission Denial: writing to secure settings\n",
            ),
        )

        result shouldBe DisplaySettingRead.PermissionDenied("Permission Denial: writing to secure settings\n")
    }

    @Test
    fun `a non-Completed outcome is reported as TransportFailed`() {
        val outcome = AdbOutcome.TransportFailure("device offline")
        val result = ShowTouchesCommand.parseRead(AdbTextResult(outcome = outcome, stdout = "", stderr = ""))

        result shouldBe DisplaySettingRead.TransportFailed(outcome)
    }

    private fun textResult(stdout: String) =
        AdbTextResult(outcome = AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = "")
}
