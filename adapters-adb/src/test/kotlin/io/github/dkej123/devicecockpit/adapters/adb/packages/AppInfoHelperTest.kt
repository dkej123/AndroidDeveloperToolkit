package io.github.dkej123.devicecockpit.adapters.adb.packages

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.packages.AppInfo
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
            "CLASSPATH=$REMOTE app_process / io.github.dkej123.devicecockpit.devicehelper.AppInfoMain 64 'com.acme.shop' 'com.gone'"
    }

    @Test
    fun `pushing a new helper version deletes the versions earlier builds left behind`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request -> if (request.isPresenceCheck()) ok("") else ok() },
            streamScript = { HELPER_OUTPUT },
        )

        AppInfoHelper(transport, BUNDLE).query(SERIAL, listOf("com.acme.shop")) {}

        transport.textRequests.map { it.shellLine() } shouldBe listOf(
            "test -f '$REMOTE' && echo present",
            null,
            "find '/data/local/tmp' -maxdepth 1 -name 'adb-toolbox-app-info-*.jar' ! -name 'adb-toolbox-app-info-v1.jar' -delete",
        )
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
    fun `a helper killed while rendering an icon is run again without that icon`() = runTest {
        // The first run aborts natively (no END) while "com.crashy" is in flight.
        val transport = FakeAdbTransport(
            textScript = { ok("present") },
            streamScript = { request ->
                if (request.shellLine()!!.contains("-n 'com.crashy'")) {
                    lines("ADBTOOLBOX-APPINFO 1", "T\tcom.crashy", "P\tcom.crashy\t-\tU2hvcA==\t-", "END")
                } else {
                    lines("ADBTOOLBOX-APPINFO 1", "T\tcom.acme.shop", "P\tcom.acme.shop\td\tU2hvcA==\t-", "T\tcom.crashy")
                }
            },
        )
        val reported = mutableListOf<AppInfo>()

        AppInfoHelper(transport, BUNDLE).query(SERIAL, listOf("com.acme.shop", "com.crashy")) { reported += it } shouldBe true

        reported.map { it.packageName } shouldBe listOf("com.acme.shop", "com.crashy")
        transport.streamRequests.map { it.shellLine() } shouldBe listOf(
            "CLASSPATH=$REMOTE app_process / io.github.dkej123.devicecockpit.devicehelper.AppInfoMain 64 'com.acme.shop' 'com.crashy'",
            "CLASSPATH=$REMOTE app_process / io.github.dkej123.devicecockpit.devicehelper.AppInfoMain 64 -n 'com.crashy' 'com.crashy'",
        )
    }

    @Test
    fun `a rerun of the full list reports each app once`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { ok("present") },
            streamScript = { request ->
                val tail = if (request.shellLine()!!.contains("-n")) listOf("P\tcom.crashy\t-\tU2hvcA==\t-", "END") else emptyList()
                lines(*(listOf("ADBTOOLBOX-APPINFO 1", "P\tcom.acme.shop\td\tU2hvcA==\t-", "T\tcom.crashy") + tail).toTypedArray())
            },
        )
        val reported = mutableListOf<String>()

        AppInfoHelper(transport, BUNDLE).query(SERIAL, (1..100).map { "com.acme.app$it" }) { reported += it.packageName }

        reported shouldBe listOf("com.acme.shop", "com.crashy")
    }

    @Test
    fun `a helper that keeps dying without a new suspect is not run again`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { ok("present") },
            streamScript = { lines("ADBTOOLBOX-APPINFO 1", "P\tcom.acme.shop\td\tU2hvcA==\t-") },
        )

        AppInfoHelper(transport, BUNDLE).query(SERIAL, listOf("com.acme.shop", "com.other")) {} shouldBe true

        transport.streamRequests shouldHaveSize 1
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
