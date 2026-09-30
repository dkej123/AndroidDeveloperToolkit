package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.display.DisplaySettingRead
import dev.acme.adbtoolbox.domain.display.developer.DevOptionsAction
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DevOptionsHelperTest {

    private val serial = DeviceSerial.of("emulator-5554")
    private val bundle = AppInfoHelperBundle(version = "v1", localPath = { "/host/helper.jar" })
    private val remote = "/data/local/tmp/adb-toolbox-app-info-v1.jar"

    private fun ok(stdout: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

    private fun AdbRequest.shellLine(): String? =
        ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Shell)?.command?.render()

    private fun AdbRequest.isPush() =
        ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Host)?.arguments?.first() == "push"

    @Test
    fun `pushes the shared helper jar once, then runs the dev-options entry point`() = runTest {
        var present = false
        val transport = FakeAdbTransport(textScript = { request ->
            when {
                request.shellLine()?.startsWith("test -f") == true -> ok(if (present) "present" else "")
                request.isPush() -> { present = true; ok() }
                else -> ok("ADBTOOLBOX-DEVOPTIONS 1\nprocess_limit=2\nEND\n")
            }
        })
        val helper = DevOptionsHelper(transport, DeviceHelperDeployment(transport, bundle))

        helper.run(serial, DevOptionsAction.SetProcessLimit(2)) shouldBe DisplaySettingRead.Value(2)
        helper.run(serial, DevOptionsAction.Read) shouldBe DisplaySettingRead.Value(2)

        transport.textRequests.count { it.isPush() } shouldBe 1
        transport.textRequests.mapNotNull { it.shellLine() }.filter { it.startsWith("CLASSPATH") } shouldBe listOf(
            "CLASSPATH=$remote app_process / dev.acme.adbtoolbox.devicehelper.DevOptionsMain process-limit 2",
            "CLASSPATH=$remote app_process / dev.acme.adbtoolbox.devicehelper.DevOptionsMain get",
        )
    }

    @Test
    fun `the app-info helper and the dev-options helper share one deployment per device`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                if (request.shellLine()?.startsWith("test -f") == true) ok("present") else ok("ADBTOOLBOX-DEVOPTIONS 1\nprocess_limit=-1\nEND")
            },
            streamScript = { listOf(AdbStreamEvent.Line("ADBTOOLBOX-APPINFO 1"), AdbStreamEvent.Completed(AdbOutcome.Completed(0))) },
        )
        val deployment = DeviceHelperDeployment(transport, bundle)

        AppInfoHelper(transport, bundle, deployment = deployment).query(serial, listOf("com.acme")) {}
        DevOptionsHelper(transport, deployment).run(serial, DevOptionsAction.Read)

        transport.textRequests.count { it.shellLine()?.startsWith("test -f") == true } shouldBe 1
    }

    @Test
    fun `a helper that cannot be materialized on the host is a readable failure`() = runTest {
        val transport = FakeAdbTransport(textScript = { ok("") })
        val missing = AppInfoHelperBundle(version = "missing", localPath = { null })

        val result = DevOptionsHelper(transport, DeviceHelperDeployment(transport, missing)).run(serial, DevOptionsAction.Read)

        result.shouldBeInstanceOf<DisplaySettingRead.Malformed>().reason shouldBe "device helper could not be installed"
    }

    @Test
    fun `a run where the helper never starts re-checks the deployment next time`() = runTest {
        var runs = 0
        val transport = FakeAdbTransport(textScript = { request ->
            when {
                request.shellLine()?.startsWith("test -f") == true -> ok("present")
                else -> { runs++; ok(if (runs == 1) "Error: Could not find class" else "ADBTOOLBOX-DEVOPTIONS 1\nprocess_limit=1\nEND") }
            }
        })
        val helper = DevOptionsHelper(transport, DeviceHelperDeployment(transport, bundle))

        helper.run(serial, DevOptionsAction.Read).shouldBeInstanceOf<DisplaySettingRead.Malformed>()
        helper.run(serial, DevOptionsAction.Read) shouldBe DisplaySettingRead.Value(1)

        transport.textRequests.count { it.shellLine()?.startsWith("test -f") == true } shouldBe 2
    }
}
