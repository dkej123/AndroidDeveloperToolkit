package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.packages.AppInfo
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveMinLength
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

private val SERIAL = DeviceSerial.of("emulator-5554")
private val BUNDLE = AppInfoHelperBundle(version = "v1", localPath = { "/host/helper.jar" })
private const val REMOTE = "/data/local/tmp/adb-toolbox-app-info-v1.jar"

private fun ok(stdout: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

private fun AdbRequest.shellLine(): String? = ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Shell)?.command?.render()

private fun AdbRequest.isPresenceCheck() = shellLine()?.startsWith("test -f") == true

private fun AdbRequest.isPush() = ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Host)?.arguments?.first() == "push"

private fun lines(vararg text: String): List<AdbStreamEvent> =
    text.map { AdbStreamEvent.Line(it) } + AdbStreamEvent.Completed(AdbOutcome.Completed(0))

// base64("Shop") = U2hvcA==
private val HELPER_OUTPUT = lines("ADBTOOLBOX-APPINFO 1", "P\tcom.acme.shop\td\tU2hvcA==\t-", "M\tcom.gone", "END")

class AppInfoHelperTest {

    @Test
    fun `pushes the helper when absent, then streams the reported apps`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request -> if (request.isPresenceCheck()) ok("") else ok() },
            streamScript = { HELPER_OUTPUT },
        )
        val reported = mutableListOf<AppInfo>()

        val ran = AppInfoHelper(transport, BUNDLE).query(SERIAL, listOf("com.acme.shop", "com.gone")) { reported += it }

        ran shouldBe true
        reported shouldBe listOf(AppInfo("com.acme.shop", "Shop", isDebuggable = true, icon = null))
        (transport.textRequests[1] as AdbDeviceRequest).operation shouldBe
            AdbOperation.Host(listOf("push", "/host/helper.jar", REMOTE))
        transport.streamRequests.single().shellLine() shouldBe
            "CLASSPATH=$REMOTE app_process / dev.acme.adbtoolbox.devicehelper.AppInfoMain 32 'com.acme.shop' 'com.gone'"
    }

    @Test
    fun `an already present helper is not pushed, and a device is only checked once`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("present\n") }, streamScript = { HELPER_OUTPUT })
        val helper = AppInfoHelper(transport, BUNDLE)

        helper.query(SERIAL, listOf("com.acme.shop")) {}
        helper.query(SERIAL, listOf("com.acme.shop")) {}

        transport.textRequests shouldHaveSize 1
        transport.textRequests.none { it.isPush() } shouldBe true
    }

    @Test
    fun `a long package list asks the helper for every installed app instead`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("present") }, streamScript = { HELPER_OUTPUT })

        AppInfoHelper(transport, BUNDLE).query(SERIAL, (1..100).map { "com.acme.app$it" }) {}

        val tokens = ((transport.streamRequests.single() as AdbDeviceRequest).operation as AdbOperation.Shell).command.tokens
        tokens.none { it is ShellToken.Value } shouldBe true
    }

    @Test
    fun `a failed push reports the helper as unavailable without running it`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                if (request.isPush()) AdbTextResult(AdbOutcome.Completed(1), "", "adb: error") else ok("")
            },
        )

        AppInfoHelper(transport, BUNDLE).query(SERIAL, listOf("com.acme.shop")) {} shouldBe false
        transport.streamRequests shouldHaveSize 0
    }

    @Test
    fun `a bundle that cannot be materialized reports the helper as unavailable`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("") })

        AppInfoHelper(transport, AppInfoHelperBundle("v1") { null }).query(SERIAL, listOf("a")) {} shouldBe false
        transport.textRequests.none { it.isPush() } shouldBe true
    }

    @Test
    fun `output without the header is ignored and the device is re-checked next time`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { ok("present") },
            streamScript = { lines("Error: Could not find class", "P\tcom.acme.shop\td\tU2hvcA==\t-") },
        )
        val helper = AppInfoHelper(transport, BUNDLE)
        val reported = mutableListOf<AppInfo>()

        helper.query(SERIAL, listOf("com.acme.shop")) { reported += it } shouldBe false
        helper.query(SERIAL, listOf("com.acme.shop")) { reported += it }

        reported shouldBe emptyList()
        transport.textRequests.count { it.isPresenceCheck() } shouldBe 2
    }

    @Test
    fun `the bundled helper is a dex jar with a content-hash version`() {
        val bundle = AppInfoHelperBundle.fromClasspath()

        bundle.version shouldHaveMinLength 12
        val path = bundle.localPath()!!
        ZipFile(File(path)).use { zip -> (zip.getEntry("classes.dex") != null) shouldBe true }
        bundle.localPath() shouldBe path
    }
}
