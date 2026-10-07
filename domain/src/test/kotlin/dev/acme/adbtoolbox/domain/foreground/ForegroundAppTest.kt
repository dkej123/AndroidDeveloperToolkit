package dev.acme.adbtoolbox.domain.foreground

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")
private const val LAUNCHER = "com.google.android.apps.nexuslauncher"
private fun fixture(name: String) = ForegroundAppTest::class.java.getResource("/foreground/$name")!!.readText()
private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")
private fun dump(activities: String, window: String = "") = ok("$activities\n--adbtoolbox-window--\n$window")

class ForegroundAppTest {

    @Test
    fun `one shell call reads the resumed activity and the window focus`() {
        (ForegroundAppCommand.request(serial).operation as AdbOperation.Shell).command.render() shouldBe
            "dumpsys activity activities | grep -E 'topResumedActivity=|ResumedActivity:|Resumed:' ; " +
            "echo --adbtoolbox-window-- ; dumpsys window | grep -E 'mCurrentFocus=|isKeyguardShowing='"
    }

    @Test
    fun `reads the app in front on API 30`() {
        ForegroundAppCommand.parse(dump(fixture("api30-settings-resumed.txt")), LAUNCHER) shouldBe
            ForegroundState.App("com.android.settings", ".Settings\$DisplaySettingsActivity")
    }

    @Test
    fun `reads topResumedActivity and the user of a work-profile app`() {
        ForegroundAppCommand.parse(dump(fixture("api34-activities-work-profile.txt")), LAUNCHER) shouldBe
            ForegroundState.App("com.acme.shop", ".checkout.CheckoutActivity", userId = 10)
    }

    @Test
    fun `the launcher in front is the home screen`() {
        ForegroundAppCommand.parse(dump(fixture("api30-launcher-resumed.txt")), LAUNCHER) shouldBe ForegroundState.Home(LAUNCHER)
    }

    @Test
    fun `the open shade is System UI over the app`() {
        ForegroundAppCommand.parse(dump(fixture("api30-settings-resumed.txt"), fixture("api30-window-shade.txt")), LAUNCHER)
            .shouldBeInstanceOf<ForegroundState.SystemUi>().behind?.packageName shouldBe "com.android.settings"
    }

    @Test
    fun `a showing keyguard is the lock screen`() {
        ForegroundAppCommand.parse(dump(fixture("api30-launcher-resumed.txt"), fixture("api30-window-lock.txt")), LAUNCHER) shouldBe
            ForegroundState.Locked
    }

    @Test
    fun `nothing resumed, and failures`() {
        ForegroundAppCommand.parse(dump(""), LAUNCHER) shouldBe ForegroundState.Nothing
        ForegroundAppCommand.parse(AdbTextResult(AdbOutcome.TimedOut, "", ""), LAUNCHER) shouldBe
            ForegroundState.Unknown("dumpsys activity activities — timed out")
    }

    @Test
    fun `resolves the home activity package`() {
        HomeActivityCommand.parse(ok("priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n$LAUNCHER/.NexusLauncherActivity\n")) shouldBe LAUNCHER
        HomeActivityCommand.parse(ok("No activity found\n")) shouldBe null
    }
}

class ProcessInfoCommandTest {

    @Test
    fun `pidof and toybox elapsed time`() {
        (ProcessInfoCommand.pidRequest(serial, "com.acme.shop").operation as AdbOperation.Shell).command.render() shouldBe "pidof -s 'com.acme.shop'"
        ProcessInfoCommand.parsePid(ok("21517\n")) shouldBe 21517
        ProcessInfoCommand.parsePid(AdbTextResult(AdbOutcome.Completed(1), "", "")) shouldBe null
        ProcessInfoCommand.parseElapsed(ok("      22:09\n")) shouldBe 22.minutes + 9.seconds
        ProcessInfoCommand.parseElapsed(ok(" 2-14:05:45\n")) shouldBe 2.days + 14.hours + 5.minutes + 45.seconds
        ProcessInfoCommand.parseElapsed(ok("01:02:03")) shouldBe 1.hours + 2.minutes + 3.seconds
        ProcessInfoCommand.parseElapsed(ok("")) shouldBe null
    }
}

class PackageDetailsCommandTest {

    @Test
    fun `reads version, SDKs, flags and runtime permissions of a real dump`() {
        val details = PackageDetailsCommand.parse(ok(fixture("api30-dumpsys-package-settings.txt")), "com.android.settings")!!

        details.versionName shouldBe "11"
        details.versionCode shouldBe 30
        details.minSdk shouldBe 30
        details.targetSdk shouldBe 30
        details.system shouldBe true
        details.debuggable shouldBe false
        details.runtimePermissions.first() shouldBe RuntimePermission(
            "android.permission.READ_CALL_LOG", granted = true,
            flags = setOf("SYSTEM_FIXED", "GRANTED_BY_DEFAULT", "RESTRICTION_SYSTEM_EXEMPT", "RESTRICTION_UPGRADE_EXEMPT"),
        )
        details.runtimePermissions.all { it.fixed } shouldBe true
    }

    @Test
    fun `an unknown package has no details`() {
        PackageDetailsCommand.parse(ok("Unable to find package: com.nope\n"), "com.nope") shouldBe null
    }

    @Test
    fun `reset revokes granted user permissions, clears their flags and skips fixed ones`() {
        val details = PackageDetails(
            versionName = "1.0", versionCode = 1, minSdk = 26, targetSdk = 36, debuggable = true, system = false,
            runtimePermissions = listOf(
                RuntimePermission("android.permission.CAMERA", granted = true, flags = setOf("USER_SET")),
                RuntimePermission("android.permission.RECORD_AUDIO", granted = false, flags = setOf("USER_FIXED")),
                RuntimePermission("android.permission.ACCESS_FINE_LOCATION", granted = true, flags = setOf("POLICY_FIXED")),
            ),
        )

        val plan = PermissionReset.plan(serial, "com.acme.shop", details, userId = 10)

        plan.revoked shouldContainExactly listOf("android.permission.CAMERA")
        plan.skippedFixed shouldContainExactly listOf("android.permission.ACCESS_FINE_LOCATION")
        plan.steps.flatMap { listOf(it.revoke, it.clearFlags) }.map { (it.operation as AdbOperation.Shell).command.render() } shouldContainExactly listOf(
            "pm revoke --user 10 'com.acme.shop' 'android.permission.CAMERA'",
            "pm clear-permission-flags --user 10 'com.acme.shop' 'android.permission.CAMERA' user-set user-fixed",
        )
    }

    @Test
    fun `reads the debuggable flag`() {
        val dump = """
            |Packages:
            |  Package [com.acme.shop] (1a2b3c):
            |    versionCode=41200 minSdk=26 targetSdk=36
            |    versionName=4.12.0-dev
            |    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
            |""".trimMargin()

        val details = PackageDetailsCommand.parse(ok(dump), "com.acme.shop")!!

        details.debuggable shouldBe true
        details.system shouldBe false
        details.versionCode shouldBe 41200
        details.runtimePermissions shouldBe emptyList()
    }
}
