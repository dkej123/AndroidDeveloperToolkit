package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.locale.LocaleAction
import dev.acme.adbtoolbox.domain.locale.LocaleRead
import dev.acme.adbtoolbox.domain.locale.LocaleTag
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LocaleHelperTest {

    private val serial = DeviceSerial.of("emulator-5554")
    private val bundle = AppInfoHelperBundle(version = "v1", localPath = { "/host/helper.jar" })
    private val remote = "/data/local/tmp/adb-toolbox-app-info-v1.jar"

    private fun ok(stdout: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

    private fun AdbRequest.shellLine(): String? =
        ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Shell)?.command?.render()

    @Test
    fun `runs the locale entry point of the shared helper jar`() = runTest {
        val transport = FakeAdbTransport(textScript = { request ->
            if (request.shellLine()?.startsWith("test -f") == true) ok("present") else ok("ADBTOOLBOX-LOCALE 1\nlocales=ar-XB\nEND")
        })
        val helper = LocaleHelper(transport, DeviceHelperDeployment(transport, bundle))

        helper.run(serial, LocaleAction.Set(listOf(LocaleTag.of("ar-XB")!!))) shouldBe LocaleRead.Locales(listOf("ar-XB"))
        transport.textRequests.mapNotNull { it.shellLine() }.last() shouldBe
            "CLASSPATH=$remote app_process / dev.acme.adbtoolbox.devicehelper.LocaleMain set ar-XB"
    }

    @Test
    fun `a helper that never started is redeployed next time`() = runTest {
        var checks = 0
        val transport = FakeAdbTransport(textScript = { request ->
            when {
                request.shellLine()?.startsWith("test -f") == true -> { checks++; ok("present") }
                else -> ok("Error: Could not find class")
            }
        })
        val helper = LocaleHelper(transport, DeviceHelperDeployment(transport, bundle))

        helper.run(serial, LocaleAction.Read) shouldBe LocaleRead.Failed("Device helper did not start")
        helper.run(serial, LocaleAction.Read)

        checks shouldBe 2
    }
}
