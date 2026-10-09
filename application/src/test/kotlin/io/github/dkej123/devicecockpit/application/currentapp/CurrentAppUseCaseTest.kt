package io.github.dkej123.devicecockpit.application.currentapp

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")
private fun AdbRequest.line() = ((this as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
private fun ok(stdout: String, stderr: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, stderr)

private const val PACKAGE_DUMP = """Packages:
  Package [com.acme.shop] (1a2b3c):
    versionCode=41200 minSdk=26 targetSdk=36
    versionName=4.12.0-dev
    flags=[ DEBUGGABLE HAS_CODE ]
    User 0: installed=true
      runtime permissions:
        android.permission.CAMERA: granted=true, flags=[ USER_SET ]
        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|USER_FIXED ]
        android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ POLICY_FIXED ]
"""

private class Device(var front: String = "com.acme.shop/.MainActivity", var pid: String = "8155") {
    val commands = mutableListOf<String>()

    fun transport() = FakeAdbTransport(textScript = { request ->
        val line = request.line()
        commands += line
        when {
            line.startsWith("cmd package resolve-activity") -> ok("com.launcher/.Home\n")
            line.startsWith("dumpsys activity activities") ->
                ok("  topResumedActivity=ActivityRecord{1 u0 $front t1}\n--adbtoolbox-window--\n  mCurrentFocus=Window{2 u0 $front}\n")
            line.startsWith("dumpsys package") -> ok(PACKAGE_DUMP)
            line.startsWith("pidof") -> if (pid.isEmpty()) AdbTextResult(AdbOutcome.Completed(1), "", "") else ok("$pid\n")
            line.startsWith("ps -o etime=") -> ok("   03:12\n")
            line.startsWith("pm revoke") -> ok("")
            line.startsWith("pm clear-permission-flags") -> ok("", "Unknown command: clear-permission-flags")
            else -> error("unscripted $line")
        }
    })
}

class CurrentAppUseCaseTest {

    @Test
    fun `reads the app in front with its facts and process`() = runTest {
        val snapshot = CurrentAppUseCase(Device().transport()).read(serial)

        snapshot.foreground shouldBe ForegroundState.App("com.acme.shop", ".MainActivity")
        snapshot.details?.versionName shouldBe "4.12.0-dev"
        snapshot.details?.debuggable shouldBe true
        snapshot.process?.pid shouldBe 8155
        snapshot.process?.runningFor shouldBe 192.seconds
    }

    @Test
    fun `a poll for the same package and PID re-reads neither the package nor the elapsed time`() = runTest {
        val device = Device()
        val useCase = CurrentAppUseCase(device.transport())
        val first = useCase.read(serial)
        device.commands.clear()

        useCase.read(serial, previous = first)

        device.commands.map { it.substringBefore(" '").substringBefore(" |") } shouldBe listOf("dumpsys activity activities", "pidof -s")
    }

    @Test
    fun `a killed app has no process, the launcher is the home screen`() = runTest {
        val device = Device(pid = "")
        val useCase = CurrentAppUseCase(device.transport())
        useCase.read(serial).process shouldBe null

        device.front = "com.launcher/.Home"
        useCase.read(serial).foreground shouldBe ForegroundState.Home("com.launcher")
    }

    @Test
    fun `reset revokes user permissions, tolerates old releases without flag clearing, skips fixed ones`() = runTest {
        val device = Device()

        CurrentAppUseCase(device.transport()).resetPermissions(serial, "com.acme.shop") shouldBe PermissionResetResult.Done(revoked = 2, skippedFixed = 1)
        device.commands.filter { it.startsWith("pm revoke") } shouldBe listOf(
            "pm revoke --user 0 'com.acme.shop' 'android.permission.CAMERA'",
            "pm revoke --user 0 'com.acme.shop' 'android.permission.POST_NOTIFICATIONS'",
        )
    }
}
