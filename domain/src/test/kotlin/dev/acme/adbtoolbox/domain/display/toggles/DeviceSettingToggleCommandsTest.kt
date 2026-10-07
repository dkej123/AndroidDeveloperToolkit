package dev.acme.adbtoolbox.domain.display.toggles

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.display.DisplaySettingRead
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")
private fun AdbDeviceRequest.rendered() = (operation as AdbOperation.Shell).command.render()
private fun ok(stdout: String, stderr: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, stderr)
private fun DeviceSettingToggleCommand.writes(enabled: Boolean) = writeRequests(serial, enabled).map { it.rendered() }

class DebugPropertyTogglesTest {

    @Test
    fun `layout bounds, overdraw and profile bars set a debug property and tell running apps`() {
        ShowLayoutBoundsCommand.readRequest(serial).rendered() shouldBe "getprop debug.layout"
        ShowLayoutBoundsCommand.writes(true) shouldBe listOf("setprop debug.layout true", "service call activity 1599295570")
        ShowLayoutBoundsCommand.writes(false) shouldBe listOf("setprop debug.layout false", "service call activity 1599295570")
        GpuOverdrawCommand.writes(true) shouldBe listOf("setprop debug.hwui.overdraw show", "service call activity 1599295570")
        GpuOverdrawCommand.writes(false) shouldBe listOf("setprop debug.hwui.overdraw false", "service call activity 1599295570")
        GpuProfileBarsCommand.writes(true) shouldBe listOf("setprop debug.hwui.profile visual_bars", "service call activity 1599295570")
    }

    @Test
    fun `an unset property is off and any visual value counts as on`() {
        ShowLayoutBoundsCommand.parseRead(ok("\n")) shouldBe DisplaySettingRead.Value(false)
        ShowLayoutBoundsCommand.parseRead(ok("true\n")) shouldBe DisplaySettingRead.Value(true)
        GpuOverdrawCommand.parseRead(ok("show_deuteranomaly\n")) shouldBe DisplaySettingRead.Value(true)
        GpuOverdrawCommand.parseRead(ok("false\n")) shouldBe DisplaySettingRead.Value(false)
        GpuProfileBarsCommand.parseRead(ok("visual_lines\n")) shouldBe DisplaySettingRead.Value(true)
        GpuProfileBarsCommand.parseRead(ok("true\n")) shouldBe DisplaySettingRead.Value(false) // gfxinfo only, nothing on screen
    }
}

class SettingTogglesTest {

    @Test
    fun `pointer location and invert colors are 1 or 0 settings, unset is off`() {
        PointerLocationCommand.readRequest(serial).rendered() shouldBe "settings get system pointer_location"
        PointerLocationCommand.writes(true) shouldBe listOf("settings put system pointer_location 1")
        InvertColorsCommand.writes(false) shouldBe listOf("settings put secure accessibility_display_inversion_enabled 0")
        InvertColorsCommand.parseRead(ok("null\n")) shouldBe DisplaySettingRead.Value(false)
        InvertColorsCommand.parseRead(ok("1\n")) shouldBe DisplaySettingRead.Value(true)
        PointerLocationCommand.parseRead(ok("maybe\n")).shouldBeInstanceOf<DisplaySettingRead.Malformed>()
    }

    @Test
    fun `bold text needs Android 12 and is a font weight adjustment`() {
        BoldTextCommand.readRequest(serial).rendered() shouldBe "getprop ro.build.version.sdk ; settings get secure font_weight_adjustment"
        BoldTextCommand.writes(true) shouldBe listOf("settings put secure font_weight_adjustment 300")
        BoldTextCommand.writes(false) shouldBe listOf("settings put secure font_weight_adjustment 0")
        BoldTextCommand.parseRead(ok("33\n300\n")) shouldBe DisplaySettingRead.Value(true)
        BoldTextCommand.parseRead(ok("33\nnull\n")) shouldBe DisplaySettingRead.Value(false)
        BoldTextCommand.parseRead(ok("30\nnull\n")) shouldBe DisplaySettingRead.NotSet
    }

    @Test
    fun `permission denials and transport failures pass through`() {
        PointerLocationCommand.parseRead(ok("", "java.lang.SecurityException: Permission Denial"))
            .shouldBeInstanceOf<DisplaySettingRead.PermissionDenied>()
        PointerLocationCommand.parseRead(AdbTextResult(AdbOutcome.TimedOut, "", "")) shouldBe
            DisplaySettingRead.TransportFailed(AdbOutcome.TimedOut)
    }
}

class ConnectivityTogglesTest {

    @Test
    fun `airplane mode goes through the connectivity service`() {
        AirplaneModeCommand.readRequest(serial).rendered() shouldBe "cmd connectivity airplane-mode"
        AirplaneModeCommand.writes(true) shouldBe listOf("cmd connectivity airplane-mode enable")
        AirplaneModeCommand.parseRead(ok("enabled\n")) shouldBe DisplaySettingRead.Value(true)
        AirplaneModeCommand.parseRead(ok("disabled\n")) shouldBe DisplaySettingRead.Value(false)
        AirplaneModeCommand.parseRead(ok("", "cmd: Can't find service: connectivity")) shouldBe DisplaySettingRead.NotSet
    }

    @Test
    fun `wi-fi and mobile data are switched with svc and read from global settings`() {
        WifiCommand.readRequest(serial).rendered() shouldBe "settings get global wifi_on"
        WifiCommand.writes(false) shouldBe listOf("svc wifi disable")
        WifiCommand.parseRead(ok("2\n")) shouldBe DisplaySettingRead.Value(true)
        WifiCommand.parseRead(ok("0\n")) shouldBe DisplaySettingRead.Value(false)
        MobileDataCommand.readRequest(serial).rendered() shouldBe
            "cmd package has-feature android.hardware.telephony ; settings get global mobile_data"
        MobileDataCommand.writes(true) shouldBe listOf("svc data enable")
        MobileDataCommand.parseRead(ok("true\nnull\n")) shouldBe DisplaySettingRead.Value(false)
        MobileDataCommand.parseRead(ok("true\n1\n")) shouldBe DisplaySettingRead.Value(true)
        MobileDataCommand.parseRead(ok("false\n1\n")) shouldBe DisplaySettingRead.NotSet // no cellular radio
    }
}

class ScreenRotationCommandTest {

    @Test
    fun `reads the auto-rotate switch and the locked rotation in one call`() {
        ScreenRotationCommand.readRequest(serial).rendered() shouldBe
            "settings get system accelerometer_rotation ; settings get system user_rotation"
        ScreenRotationCommand.parseRead(ok("1\n0\n")) shouldBe DisplaySettingRead.Value(ScreenRotation.Auto)
        ScreenRotationCommand.parseRead(ok("0\n0\n")) shouldBe DisplaySettingRead.Value(ScreenRotation.Portrait)
        ScreenRotationCommand.parseRead(ok("0\n1\n")) shouldBe DisplaySettingRead.Value(ScreenRotation.Landscape)
        ScreenRotationCommand.parseRead(ok("0\n3\n")) shouldBe DisplaySettingRead.Value(ScreenRotation.Landscape)
        ScreenRotationCommand.parseRead(ok("0\n2\n")) shouldBe DisplaySettingRead.Value(ScreenRotation.Portrait)
        ScreenRotationCommand.parseRead(ok("null\nnull\n")) shouldBe DisplaySettingRead.Value(ScreenRotation.Portrait)
        ScreenRotationCommand.parseRead(ok("x\n")).shouldBeInstanceOf<DisplaySettingRead.Malformed>()
    }

    @Test
    fun `locking turns auto-rotate off first, auto turns it back on`() {
        ScreenRotationCommand.writeRequests(serial, ScreenRotation.Landscape).map { it.rendered() } shouldBe listOf(
            "settings put system accelerometer_rotation 0",
            "settings put system user_rotation 1",
        )
        ScreenRotationCommand.writeRequests(serial, ScreenRotation.Portrait).map { it.rendered() } shouldBe listOf(
            "settings put system accelerometer_rotation 0",
            "settings put system user_rotation 0",
        )
        ScreenRotationCommand.writeRequests(serial, ScreenRotation.Auto).map { it.rendered() } shouldBe
            listOf("settings put system accelerometer_rotation 1")
    }
}
