package dev.acme.adbtoolbox.domain.display

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DarkThemeCommandTest {

    private val serial = DeviceSerial.of("emulator-5554")

    @Test
    fun `read request targets the exact serial with cmd uimode night`() {
        val request = DarkThemeCommand.readRequest(serial)

        request.serial shouldBe serial
        (request.operation as AdbOperation.Shell).command.render() shouldBe "cmd uimode night"
    }

    @Test
    fun `write request renders yes for enabled`() {
        val request = DarkThemeCommand.writeRequest(serial, enabled = true)

        (request.operation as AdbOperation.Shell).command.render() shouldBe "cmd uimode night yes"
    }

    @Test
    fun `write request renders no for disabled`() {
        val request = DarkThemeCommand.writeRequest(serial, enabled = false)

        (request.operation as AdbOperation.Shell).command.render() shouldBe "cmd uimode night no"
    }

    @Test
    fun `parses normal yes output as true`() {
        val result = DarkThemeCommand.parseRead(textResult(stdout = "Night mode: yes\n"))

        result shouldBe DisplaySettingRead.Value(true)
    }

    @Test
    fun `parses normal no output as false`() {
        val result = DarkThemeCommand.parseRead(textResult(stdout = "Night mode: no\n"))

        result shouldBe DisplaySettingRead.Value(false)
    }

    @Test
    fun `parses malformed output as Malformed, not a crash`() {
        val result = DarkThemeCommand.parseRead(textResult(stdout = "garbled nonsense\n"))

        result shouldBe DisplaySettingRead.Malformed(
            raw = "garbled nonsense\n",
            reason = "expected 'Night mode: yes' or 'Night mode: no'",
        )
    }

    @Test
    fun `parses an unknown-command response as NotSet, an API level that lacks the uimode service`() {
        val result = DarkThemeCommand.parseRead(
            textResult(stdout = "", stderr = "Error: unknown command 'night'\n"),
        )

        result shouldBe DisplaySettingRead.NotSet
    }

    @Test
    fun `parses a permission denial in stderr as PermissionDenied`() {
        val result = DarkThemeCommand.parseRead(
            textResult(stdout = "", stderr = "Permission Denial: not allowed to change night mode\n"),
        )

        result shouldBe DisplaySettingRead.PermissionDenied("Permission Denial: not allowed to change night mode\n")
    }

    @Test
    fun `a non-Completed outcome is reported as TransportFailed, never parsed as stdout`() {
        val outcome = AdbOutcome.TimedOut
        val result = DarkThemeCommand.parseRead(
            AdbTextResult(outcome = outcome, stdout = "", stderr = ""),
        )

        result shouldBe DisplaySettingRead.TransportFailed(outcome)
    }

    private fun textResult(stdout: String, stderr: String = "") =
        AdbTextResult(outcome = AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = stderr)
}
