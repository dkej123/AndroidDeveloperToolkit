package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.capture.FullShotRender
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FullShotHelperTest {

    private val serial = DeviceSerial.of("emulator-5554")
    private val bundle = AppInfoHelperBundle(version = "v1", localPath = { "/host/helper.jar" })
    private val remote = "/data/local/tmp/adb-toolbox-app-info-v1.jar"
    private val rendered = "ADBTOOLBOX-FULLSHOT 1\nfile=/data/local/tmp/adbtoolbox-fullshot.png\nsize=1080x5000\ntruncated=0\nEND\n"

    private fun ok(stdout: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

    private fun AdbRequest.shellLine(): String? =
        ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Shell)?.command?.render()

    @Test
    fun `runs the full-shot entry point of the shared, deployed helper`() = runTest {
        val transport = FakeAdbTransport(textScript = { request ->
            if (request.shellLine()?.startsWith("test -f") == true) ok("present") else ok(rendered)
        })

        val result = FullShotHelper(transport, DeviceHelperDeployment(transport, bundle)).render(serial)

        result shouldBe FullShotRender.Rendered("/data/local/tmp/adbtoolbox-fullshot.png", 1080, 5000, truncated = false)
        transport.textRequests.mapNotNull { it.shellLine() }.filter { it.startsWith("CLASSPATH") } shouldBe listOf(
            "CLASSPATH=$remote app_process / dev.acme.adbtoolbox.devicehelper.FullShotMain '/data/local/tmp/adbtoolbox-fullshot.png'",
        )
    }

    @Test
    fun `a helper that cannot be materialized on the host is a readable failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("") })
        val missing = AppInfoHelperBundle(version = "missing", localPath = { null })

        FullShotHelper(transport, DeviceHelperDeployment(transport, missing)).render(serial) shouldBe
            FullShotRender.Failed("Device helper could not be installed", helperStarted = false)
    }

    @Test
    fun `a run where the helper never starts re-checks the deployment next time`() = runTest {
        var runs = 0
        val transport = FakeAdbTransport(textScript = { request ->
            when {
                request.shellLine()?.startsWith("test -f") == true -> ok("present")
                else -> { runs++; ok(if (runs == 1) "Error: Could not find class" else rendered) }
            }
        })
        val helper = FullShotHelper(transport, DeviceHelperDeployment(transport, bundle))

        (helper.render(serial) as FullShotRender.Failed).helperStarted shouldBe false
        (helper.render(serial) is FullShotRender.Rendered) shouldBe true

        transport.textRequests.count { it.shellLine()?.startsWith("test -f") == true } shouldBe 2
    }

    @Test
    fun `a helper error does not force a redeploy`() = runTest {
        val transport = FakeAdbTransport(textScript = { request ->
            if (request.shellLine()?.startsWith("test -f") == true) {
                ok("present")
            } else {
                ok("ADBTOOLBOX-FULLSHOT 1\nerror=java.lang.IllegalStateException: no app is in the foreground\nEND\n")
            }
        })
        val helper = FullShotHelper(transport, DeviceHelperDeployment(transport, bundle))

        helper.render(serial)
        helper.render(serial)

        transport.textRequests.count { it.shellLine()?.startsWith("test -f") == true } shouldBe 1
    }
}
