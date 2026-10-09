package io.github.dkej123.devicecockpit.domain.display.developer

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.display.DisplaySettingRead
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class DevOptionsHelperCommandTest {

    private val serial = DeviceSerial.of("emulator-5554")
    private val remotePath = "/data/local/tmp/adb-toolbox-app-info-0123abcd.jar"

    private fun render(action: DevOptionsAction) =
        (DevOptionsHelperCommand.request(serial, remotePath, action).operation as AdbOperation.Shell).command.render()

    @Test
    fun `runs the dev-options entry point of the pushed helper with app_process`() {
        val prefix = "CLASSPATH=$remotePath app_process / io.github.dkej123.devicecockpit.devicehelper.DevOptionsMain"

        render(DevOptionsAction.Read) shouldBe "$prefix get"
        render(DevOptionsAction.SetProcessLimit(2)) shouldBe "$prefix process-limit 2"
        render(DevOptionsAction.SetProcessLimit(BackgroundProcessLimit.STANDARD)) shouldBe "$prefix process-limit -1"
        render(DevOptionsAction.SetAlwaysFinish(true)) shouldBe "$prefix always-finish 1"
        render(DevOptionsAction.SetAlwaysFinish(false)) shouldBe "$prefix always-finish 0"
    }

    @Test
    fun `parses the reported process limit`() {
        val result = ok("ADBTOOLBOX-DEVOPTIONS 1\nprocess_limit=3\nEND\n")

        DevOptionsHelperCommand.parse(result) shouldBe DisplaySettingRead.Value(3)
    }

    @Test
    fun `a security exception from the helper is a permission denial`() {
        val result = ok("ADBTOOLBOX-DEVOPTIONS 1\nerror=java.lang.SecurityException: Permission Denial: setProcessLimit\nEND\n")

        DevOptionsHelperCommand.parse(result) shouldBe
            DisplaySettingRead.PermissionDenied("java.lang.SecurityException: Permission Denial: setProcessLimit")
    }

    @Test
    fun `any other helper error, a missing header or a missing value is Malformed`() {
        DevOptionsHelperCommand.parse(ok("ADBTOOLBOX-DEVOPTIONS 1\nerror=NoSuchMethodException\nEND\n"))
            .shouldBeInstanceOf<DisplaySettingRead.Malformed>()
        DevOptionsHelperCommand.parse(ok("Error: Could not find or load main class\n"))
            .shouldBeInstanceOf<DisplaySettingRead.Malformed>()
        DevOptionsHelperCommand.parse(ok("ADBTOOLBOX-DEVOPTIONS 1\nEND\n"))
            .shouldBeInstanceOf<DisplaySettingRead.Malformed>()
    }

    @Test
    fun `a transport failure is kept as-is`() {
        val outcome = AdbOutcome.TimedOut

        DevOptionsHelperCommand.parse(AdbTextResult(outcome, "", "")) shouldBe DisplaySettingRead.TransportFailed(outcome)
    }

    @Test
    fun `presets are Standard then zero to four processes`() {
        BackgroundProcessLimit.PRESETS shouldBe listOf(-1, 0, 1, 2, 3, 4)
        BackgroundProcessLimit.label(-1) shouldBe "Standard limit"
        BackgroundProcessLimit.label(0) shouldBe "No background processes"
        BackgroundProcessLimit.label(1) shouldBe "At most 1 process"
        BackgroundProcessLimit.label(4) shouldBe "At most 4 processes"
    }

    private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(exitCode = 0), stdout = stdout, stderr = "")
}
