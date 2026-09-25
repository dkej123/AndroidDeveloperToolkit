package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("emulator-5554")
private const val REMOTE = "/data/local/tmp/adb-toolbox-app-info-abc123.jar"

// base64("Acme Shop"), base64("日本アプリ"), base64("two\tpart\nlabel"), base64 of 4 arbitrary bytes.
private const val ACME_SHOP_B64 = "QWNtZSBTaG9w"
private const val JAPANESE_B64 = "5pel5pys44Ki44OX44Oq"
private const val CONTROL_CHARS_B64 = "dHdvCXBhcnQKbGFiZWw="
private const val ICON_B64 = "iVBORw=="

class AppInfoCommandTest {

    @Test
    fun `the remote path is versioned under the shell-writable tmp dir`() {
        AppInfoCommand.remotePath("abc123") shouldBe REMOTE
    }

    @Test
    fun `a helper version that is not a plain token is rejected`() {
        shouldThrow<IllegalArgumentException> { AppInfoCommand.remotePath("a b;rm") }
    }

    @Test
    fun `renders the presence check`() {
        val request = AppInfoCommand.presenceRequest(SERIAL, REMOTE)

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "test -f '$REMOTE' && echo present"
    }

    @Test
    fun `presence is only the exact marker`() {
        AppInfoCommand.isPresent(AdbTextResult(AdbOutcome.Completed(0), "present\n", "")) shouldBe true
        AppInfoCommand.isPresent(AdbTextResult(AdbOutcome.Completed(1), "", "")) shouldBe false
        AppInfoCommand.isPresent(AdbTextResult(AdbOutcome.TimedOut, "present", "")) shouldBe false
    }

    @Test
    fun `renders the push as literal host argv`() {
        val request = AppInfoCommand.pushRequest(SERIAL, "/tmp/local helper.jar", REMOTE)

        request.operation shouldBe AdbOperation.Host(listOf("push", "/tmp/local helper.jar", REMOTE))
    }

    @Test
    fun `renders the helper run with quoted package names`() {
        val request = AppInfoCommand.request(SERIAL, REMOTE, iconSizePx = 32, packages = listOf("com.acme.shop", "com.o'dd"))

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "CLASSPATH=$REMOTE app_process / dev.acme.adbtoolbox.devicehelper.AppInfoMain 32 'com.acme.shop' 'com.o'\\''dd'"
        request.timeout shouldNotBe null
    }

    @Test
    fun `renders the all-packages helper run without package arguments`() {
        val request = AppInfoCommand.request(SERIAL, REMOTE, iconSizePx = 32, packages = emptyList())

        (request.operation as AdbOperation.Shell).command.render() shouldBe
            "CLASSPATH=$REMOTE app_process / dev.acme.adbtoolbox.devicehelper.AppInfoMain 32"
    }

    @Test
    fun `parses the header and end markers`() {
        AppInfoCommand.parseLine("ADBTOOLBOX-APPINFO 1") shouldBe AppInfoLine.Header
        AppInfoCommand.parseLine("END\r") shouldBe AppInfoLine.End
    }

    @Test
    fun `parses a debuggable package with label and icon`() {
        val line = AppInfoCommand.parseLine("P\tcom.acme.shop\td\t$ACME_SHOP_B64\t$ICON_B64")

        line shouldBe AppInfoLine.Found(
            AppInfo(
                packageName = "com.acme.shop",
                label = "Acme Shop",
                isDebuggable = true,
                icon = AppIcon(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
            ),
        )
    }

    @Test
    fun `parses a non-ascii label, a release flag and a missing icon`() {
        AppInfoCommand.parseLine("P\tcom.acme.jp\t-\t$JAPANESE_B64\t-") shouldBe AppInfoLine.Found(
            AppInfo(packageName = "com.acme.jp", label = "日本アプリ", isDebuggable = false, icon = null),
        )
    }

    @Test
    fun `control characters in a label are flattened to single spaces`() {
        val line = AppInfoCommand.parseLine("P\tcom.acme.x\t-\t$CONTROL_CHARS_B64\t-") as AppInfoLine.Found

        line.info.label shouldBe "two part label"
    }

    @Test
    fun `a blank label falls back to null so the caller applies the package-name fallback`() {
        val line = AppInfoCommand.parseLine("P\tcom.acme.x\t-\t\t-") as AppInfoLine.Found

        line.info.label shouldBe null
    }

    @Test
    fun `an undecodable icon only drops the icon`() {
        val line = AppInfoCommand.parseLine("P\tcom.acme.shop\t-\t$ACME_SHOP_B64\t***") as AppInfoLine.Found

        line.info.icon shouldBe null
        line.info.label shouldBe "Acme Shop"
    }

    @Test
    fun `parses a missing package`() {
        AppInfoCommand.parseLine("M\tcom.gone") shouldBe AppInfoLine.Missing("com.gone")
    }

    @Test
    fun `anything else is malformed rather than thrown`() {
        (AppInfoCommand.parseLine("Error: Could not find or load main class") is AppInfoLine.Malformed) shouldBe true
        (AppInfoCommand.parseLine("P\tcom.acme.shop\td") is AppInfoLine.Malformed) shouldBe true
        (AppInfoCommand.parseLine("P\tcom.acme.shop\tx\t$ACME_SHOP_B64\t-") is AppInfoLine.Malformed) shouldBe true
        (AppInfoCommand.parseLine("P\tcom.acme.shop\td\t%%%\t-") is AppInfoLine.Malformed) shouldBe true
    }

    @Test
    fun `icons compare by content`() {
        AppIcon(byteArrayOf(1, 2)) shouldBe AppIcon(byteArrayOf(1, 2))
        AppIcon(byteArrayOf(1, 2)).hashCode() shouldBe AppIcon(byteArrayOf(1, 2)).hashCode()
        AppIcon(byteArrayOf(1, 2)) shouldNotBe AppIcon(byteArrayOf(2, 1))
    }
}
