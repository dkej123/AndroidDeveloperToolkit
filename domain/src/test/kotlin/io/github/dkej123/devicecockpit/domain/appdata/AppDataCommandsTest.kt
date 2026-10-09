package io.github.dkej123.devicecockpit.domain.appdata

import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("emulator-5554")
private const val PKG = "com.acme.shop"

private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

private fun AdbOperation.line() = (this as AdbOperation.Shell).command.render()

class AppDataCommandsTest {

    @Test
    fun `access probes check for a root shell, run-as, then su`() {
        AppDataCommands.shellUidRequest(SERIAL).operation.line() shouldBe "id -u"
        AppDataCommands.runAsProbeRequest(SERIAL, PKG).operation.line() shouldBe "run-as 'com.acme.shop' id -u"
        AppDataCommands.suProbeRequest(SERIAL).operation.line() shouldBe "su 0 id -u"
        AppDataCommands.isRootUid(ok("0\n")) shouldBe true
        AppDataCommands.isRootUid(ok("2000")) shouldBe false
        AppDataCommands.isNumericUid(ok("10123\n")) shouldBe true
        AppDataCommands.isNumericUid(ok("run-as: package not debuggable: com.acme.shop")) shouldBe false
    }

    @Test
    fun `commands run in the app's data directory for each access mode`() {
        val inner = "ls -1 shared_prefs"

        AppDataCommands.inApp(SERIAL, AppDataAccess.RootShell, PKG, inner).operation.line() shouldBe
            "sh -c 'cd /data/data/com.acme.shop && ls -1 shared_prefs'"
        AppDataCommands.inApp(SERIAL, AppDataAccess.RunAs, PKG, inner).operation.line() shouldBe
            "run-as 'com.acme.shop' sh -c 'ls -1 shared_prefs'"
        AppDataCommands.inApp(SERIAL, AppDataAccess.Su, PKG, inner).operation.line() shouldBe
            "su 0 sh -c 'cd /data/data/com.acme.shop && ls -1 shared_prefs'"
    }

    @Test
    fun `file names are quoted inside the app command`() {
        AppDataCommands.catCommand("shared_prefs/o'dd name.xml") shouldBe "cat 'shared_prefs/o'\\''dd name.xml'"
    }

    @Test
    fun `the file listing separates prefs and databases and drops sqlite side files`() {
        val listing = AppDataCommands.parseListing(
            ok("settings.xml\ncom.acme.shop_preferences.xml\n---\napp.db\napp.db-wal\napp.db-shm\nold.db-journal\ncache.sqlite\n"),
        )

        listing shouldBe AppDataListing(
            sharedPrefs = listOf("com.acme.shop_preferences.xml", "settings.xml"),
            databases = listOf("app.db", "cache.sqlite"),
        )
    }

    @Test
    fun `writing a file streams base64 chunks into a temp file then overwrites the target in place`() {
        val commands = AppDataCommands.writeCommands("shared_prefs/a.xml", "hello world".encodeToByteArray(), chunkSize = 8)

        commands shouldBe listOf(
            "printf '%s' 'aGVsbG8g' | base64 -d > '.adbtoolbox-upload.tmp'",
            "printf '%s' 'd29ybGQ=' | base64 -d >> '.adbtoolbox-upload.tmp'",
            // `cat >` keeps the target's owner and SELinux label; a `mv` of a root-written file would not.
            "cat '.adbtoolbox-upload.tmp' > 'shared_prefs/a.xml' && rm -f '.adbtoolbox-upload.tmp'",
        )
    }
}
