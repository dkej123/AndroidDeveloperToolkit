package dev.acme.adbtoolbox.domain.display.developer

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

private fun ok(stdout: String, stderr: String = "") =
    AdbTextResult(outcome = AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = stderr)

class StayAwakeCommandTest {

    @Test
    fun `reads the global stay_on_while_plugged_in setting`() {
        StayAwakeCommand.readRequest(serial).rendered() shouldBe "settings get global stay_on_while_plugged_in"
    }

    @Test
    fun `writes through svc power stayon, which picks the right plug types itself`() {
        StayAwakeCommand.writeRequests(serial, enabled = true).map { it.rendered() } shouldBe listOf("svc power stayon true")
        StayAwakeCommand.writeRequests(serial, enabled = false).map { it.rendered() } shouldBe listOf("svc power stayon false")
    }

    @Test
    fun `any plug-type mask is on, zero and the unset sentinel are off`() {
        StayAwakeCommand.parseRead(ok("7\n")) shouldBe DisplaySettingRead.Value(true)
        StayAwakeCommand.parseRead(ok("2\n")) shouldBe DisplaySettingRead.Value(true)
        StayAwakeCommand.parseRead(ok("0\n")) shouldBe DisplaySettingRead.Value(false)
        StayAwakeCommand.parseRead(ok("null\n")) shouldBe DisplaySettingRead.Value(false)
    }

    @Test
    fun `non-numeric output is Malformed and a transport failure is kept`() {
        StayAwakeCommand.parseRead(ok("yes\n")).shouldBeInstanceOf<DisplaySettingRead.Malformed>()
        val outcome = AdbOutcome.TransportFailure("device offline")
        StayAwakeCommand.parseRead(AdbTextResult(outcome, "", "")) shouldBe DisplaySettingRead.TransportFailed(outcome)
    }
}

class AlwaysFinishActivitiesCommandTest {

    @Test
    fun `reads the global always_finish_activities setting`() {
        AlwaysFinishActivitiesCommand.readRequest(serial).rendered() shouldBe "settings get global always_finish_activities"
    }

    @Test
    fun `1 is on, 0 and the never-set sentinel are the default off`() {
        AlwaysFinishActivitiesCommand.parseRead(ok("1\n")) shouldBe DisplaySettingRead.Value(true)
        AlwaysFinishActivitiesCommand.parseRead(ok("0\n")) shouldBe DisplaySettingRead.Value(false)
        AlwaysFinishActivitiesCommand.parseRead(ok("null\n")) shouldBe DisplaySettingRead.Value(false)
        AlwaysFinishActivitiesCommand.parseRead(ok("2\n")).shouldBeInstanceOf<DisplaySettingRead.Malformed>()
    }
}

class ShowViewUpdatesCommandTest {

    @Test
    fun `reads the hwui dirty-regions debug property`() {
        ShowViewUpdatesCommand.readRequest(serial).rendered() shouldBe "getprop debug.hwui.show_dirty_regions"
    }

    @Test
    fun `writes the property, then pokes running apps to re-read system properties`() {
        ShowViewUpdatesCommand.writeRequests(serial, enabled = true).map { it.rendered() } shouldBe listOf(
            "setprop debug.hwui.show_dirty_regions true",
            "service call activity 1599295570",
        )
        ShowViewUpdatesCommand.writeRequests(serial, enabled = false).first().rendered() shouldBe
            "setprop debug.hwui.show_dirty_regions false"
    }

    @Test
    fun `true is on, an empty or false property is off`() {
        ShowViewUpdatesCommand.parseRead(ok("true\n")) shouldBe DisplaySettingRead.Value(true)
        ShowViewUpdatesCommand.parseRead(ok("false\n")) shouldBe DisplaySettingRead.Value(false)
        ShowViewUpdatesCommand.parseRead(ok("\n")) shouldBe DisplaySettingRead.Value(false)
        ShowViewUpdatesCommand.parseRead(ok("maybe\n")).shouldBeInstanceOf<DisplaySettingRead.Malformed>()
    }
}

class ShowSurfaceUpdatesCommandTest {

    @Test
    fun `reads SurfaceFlinger's debug state and writes its show-updates code`() {
        ShowSurfaceUpdatesCommand.readRequest(serial).rendered() shouldBe "service call SurfaceFlinger 1010"
        ShowSurfaceUpdatesCommand.writeRequests(serial, enabled = true).map { it.rendered() } shouldBe
            listOf("service call SurfaceFlinger 1002 i32 1")
        // 0 toggles rather than clears — callers only send it while the readback says "on".
        ShowSurfaceUpdatesCommand.writeRequests(serial, enabled = false).map { it.rendered() } shouldBe
            listOf("service call SurfaceFlinger 1002 i32 0")
    }

    @Test
    fun `the third reply word is the show-updates flag`() {
        val on = """
            Result: Parcel(
              0x00000000: 00000000 00000000 00000001 00000000 '................'
              0x00000010: 00000000                            '....            ')
        """.trimIndent()
        val off = on.replace("00000000 00000000 00000001", "00000000 00000000 00000000")

        ShowSurfaceUpdatesCommand.parseRead(ok(on)) shouldBe DisplaySettingRead.Value(true)
        ShowSurfaceUpdatesCommand.parseRead(ok(off)) shouldBe DisplaySettingRead.Value(false)
    }

    @Test
    fun `newer builds report a flash delay instead of 1, still meaning on`() {
        val reply = "Result: Parcel(\n  0x00000000: 00000000 00000000 00000004 00000000 '................')\n"

        ShowSurfaceUpdatesCommand.parseRead(ok(reply)) shouldBe DisplaySettingRead.Value(true)
    }

    @Test
    fun `a shell-user rejection explains that adb root is needed`() {
        val denied = "Result: Parcel(Error: 0xffffffff \"Operation not permitted\")\n"

        val read = ShowSurfaceUpdatesCommand.parseRead(ok(denied))

        read shouldBe DisplaySettingRead.PermissionDenied(ShowSurfaceUpdatesCommand.NEEDS_ROOT_MESSAGE)
    }

    @Test
    fun `a short or unrecognised reply is Malformed`() {
        ShowSurfaceUpdatesCommand.parseRead(ok("Result: Parcel(NULL)\n")).shouldBeInstanceOf<DisplaySettingRead.Malformed>()
        ShowSurfaceUpdatesCommand.parseRead(ok("service: command not found\n"))
            .shouldBeInstanceOf<DisplaySettingRead.Malformed>()
    }
}
