package io.github.dkej123.devicecockpit.domain.display

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("R58N90ABCDE")

private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

private fun AdbOperation.line(): String = (this as AdbOperation.Shell).command.render()

class TalkBackCommandTest {

    @Test
    fun `detects which known TalkBack is installed`() {
        TalkBackCommand.detectionRequest(SERIAL).operation.line() shouldBe "pm list packages talkback"
        TalkBackCommand.vendorOf(ok("package:com.samsung.android.accessibility.talkback\n")) shouldBe TalkBackVendor.Samsung
        TalkBackCommand.vendorOf(ok("package:com.google.android.marvin.talkback\r\n")) shouldBe TalkBackVendor.Google
        // One UI with Google's TalkBack side-loaded still uses the system (Samsung) service.
        TalkBackCommand.vendorOf(
            ok("package:com.google.android.marvin.talkback\npackage:com.samsung.android.accessibility.talkback\n"),
        ) shouldBe TalkBackVendor.Samsung
        // e.g. Huawei without GMS: no known component, so no guess.
        TalkBackCommand.vendorOf(ok("package:com.huawei.talkback.other\n")) shouldBe null
        TalkBackCommand.vendorOf(ok("")) shouldBe null
        TalkBackCommand.vendorOf(AdbTextResult(AdbOutcome.TimedOut, "", "")) shouldBe null
    }

    @Test
    fun `without a known TalkBack there is no default on-command, but off still works`() {
        val commands = TalkBackCommand.resolve(null, TalkBackCustomCommands())

        commands.on shouldBe null
        commands.off shouldBe TalkBackCommand.resolve(TalkBackVendor.Google, TalkBackCustomCommands()).off
        commands.profile shouldBe null
        TalkBackCommand.resolve(null, TalkBackCustomCommands(on = "custom on")).on shouldBe "custom on"
    }

    @Test
    fun `samsung uses its own TalkBack service`() {
        val commands = TalkBackCommand.resolve(TalkBackVendor.Samsung, TalkBackCustomCommands())

        commands.on shouldBe "settings put secure accessibility_enabled 1 && settings put secure enabled_accessibility_services " +
            "com.samsung.android.accessibility.talkback/com.samsung.android.marvin.talkback.TalkBackService"
        commands.off shouldBe
            "settings put secure enabled_accessibility_services null && settings put secure accessibility_enabled 0"
        commands.profile shouldBe TalkBackProfile.Samsung
    }

    @Test
    fun `google devices use Google's TalkBack service`() {
        val commands = TalkBackCommand.resolve(TalkBackVendor.Google, TalkBackCustomCommands())

        commands.on shouldBe "settings put secure accessibility_enabled 1 && settings put secure enabled_accessibility_services " +
            "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        commands.profile shouldBe TalkBackProfile.Google
    }

    @Test
    fun `custom commands override the vendor defaults one direction at a time`() {
        val commands = TalkBackCommand.resolve(TalkBackVendor.Samsung, TalkBackCustomCommands(on = "cmd a11y on", off = null))

        commands.on shouldBe "cmd a11y on"
        commands.off shouldBe TalkBackCommand.resolve(TalkBackVendor.Samsung, TalkBackCustomCommands()).off
        commands.profile shouldBe TalkBackProfile.Custom
    }

    @Test
    fun `a custom command pasted with adb shell prefixes is reduced to the remote shell line`() {
        TalkBackCommand.normalizeCustom(
            "adb shell settings put secure accessibility_enabled 1 && adb shell settings put secure x y",
        ) shouldBe "settings put secure accessibility_enabled 1 && settings put secure x y"
        TalkBackCommand.normalizeCustom("  adb -s R58N90ABCDE shell settings put secure a 1 ") shouldBe "settings put secure a 1"
        TalkBackCommand.normalizeCustom("settings put secure a 1") shouldBe "settings put secure a 1"
        TalkBackCommand.normalizeCustom("   ") shouldBe null
        TalkBackCommand.normalizeCustom(null) shouldBe null
    }

    @Test
    fun `the write runs the command line through the device shell as-is`() {
        TalkBackCommand.writeRequest(SERIAL, "settings put secure a 1 && settings put secure b 2").operation.line() shouldBe
            "settings put secure a 1 && settings put secure b 2"
    }

    @Test
    fun `talkback is on while an enabled accessibility service is a TalkBack service`() {
        TalkBackCommand.readRequest(SERIAL).operation.line() shouldBe "settings get secure enabled_accessibility_services"
        TalkBackCommand.parseRead(
            ok("com.samsung.android.accessibility.talkback/com.samsung.android.marvin.talkback.TalkBackService\n"),
        ) shouldBe DisplaySettingRead.Value(true)
        TalkBackCommand.parseRead(ok("com.other/.Service:com.google.android.marvin.talkback/.TalkBackService")) shouldBe
            DisplaySettingRead.Value(true)
        TalkBackCommand.parseRead(ok("com.other/.Service")) shouldBe DisplaySettingRead.Value(false)
        TalkBackCommand.parseRead(ok("null\n")) shouldBe DisplaySettingRead.Value(false)
        TalkBackCommand.parseRead(ok("")) shouldBe DisplaySettingRead.Value(false)
    }

    @Test
    fun `transport failures and permission denials are reported, not read as off`() {
        TalkBackCommand.parseRead(AdbTextResult(AdbOutcome.TimedOut, "", "")) shouldBe
            DisplaySettingRead.TransportFailed(AdbOutcome.TimedOut)
        (TalkBackCommand.parseRead(AdbTextResult(AdbOutcome.Completed(1), "", "java.lang.SecurityException: Permission denial")) is
            DisplaySettingRead.PermissionDenied) shouldBe true
    }
}
