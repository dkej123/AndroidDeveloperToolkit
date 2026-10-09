package dev.acme.adbtoolbox.application.layout

import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")
private const val DUMP = """<hierarchy><node class="a.Root" bounds="[0,0][1080,2340]"><node class="a.Button" bounds="[0,0][440,440]"/></node></hierarchy>"""

private fun AdbRequest.shellLine(): String? = ((this as? AdbDeviceRequest)?.operation as? AdbOperation.Shell)?.command?.render()
private fun ok(stdout: String) = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

class CaptureLayoutUseCaseTest {

    private fun transport(dumps: MutableList<String>, density: String = "Physical density: 440\n") = FakeAdbTransport(
        textScript = { request ->
            when {
                request.shellLine() == "wm density" -> ok(density)
                request.shellLine()?.startsWith("uiautomator dump") == true -> ok(dumps.removeFirstOrNull() ?: "")
                else -> error("unexpected $request")
            }
        },
        binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1, 2), byteArrayOf(3)), AdbOutcome.Completed(0)) },
    )

    @Test
    fun `reads the hierarchy with the effective density`() = runTest {
        val useCase = CaptureLayoutUseCase(transport(mutableListOf(DUMP), "Physical density: 440\nOverride density: 560\n"))

        val hierarchy = useCase.hierarchy(serial).shouldBeInstanceOf<LayoutCapture.Captured<*>>().value as dev.acme.adbtoolbox.domain.layout.UiHierarchy

        hierarchy.densityDpi shouldBe 560
        hierarchy.root.children.single().className shouldBe "a.Button"
    }

    @Test
    fun `retries with backoff while uiautomator prints nothing`() = runTest {
        val dumps = mutableListOf("", "", DUMP)
        val useCase = CaptureLayoutUseCase(transport(dumps))

        useCase.hierarchy(serial).shouldBeInstanceOf<LayoutCapture.Captured<*>>()
        currentTime shouldBe 1_500
    }

    @Test
    fun `three empty dumps suggest turning animations off`() = runTest {
        CaptureLayoutUseCase(transport(mutableListOf("", "", ""))).hierarchy(serial) shouldBe
            LayoutCapture.Failed(
                "uiautomator returned nothing: the screen kept animating. Wait and retry, or turn animations off " +
                    "(MCP: set_device_settings animations=false).",
            )
    }

    @Test
    fun `an unreadable density or dump fails with the reason`() = runTest {
        CaptureLayoutUseCase(transport(mutableListOf(DUMP), "Error: denied")).hierarchy(serial)
            .shouldBeInstanceOf<LayoutCapture.Failed>().reason shouldBe "Could not read the display density."
        CaptureLayoutUseCase(transport(mutableListOf("<hierarchy><node>"))).hierarchy(serial)
            .shouldBeInstanceOf<LayoutCapture.Failed>().reason shouldBe "Could not read the view hierarchy: 1 <node> element(s) never closed."
    }

    @Test
    fun `a snapshot carries the screenshot bytes of the same capture`() = runTest {
        val fake = transport(mutableListOf(DUMP))

        val snapshot = CaptureLayoutUseCase(fake).snapshot(serial).shouldBeInstanceOf<LayoutCapture.Captured<*>>().value as LayoutSnapshot

        snapshot.png.toList() shouldBe listOf<Byte>(1, 2, 3)
        snapshot.hierarchy.densityDpi shouldBe 440
        ((fake.binaryRequests.single() as AdbDeviceRequest).operation as AdbOperation.Exec).arguments shouldBe listOf("screencap", "-p")
    }

    @Test
    fun `a failed screenshot fails the snapshot`() = runTest {
        val fake = FakeAdbTransport(
            textScript = { request -> if (request.shellLine() == "wm density") ok("Physical density: 440") else ok(DUMP) },
            binaryScript = { AdbBinaryScript(emptyList(), AdbOutcome.TransportFailure("offline")) },
        )

        CaptureLayoutUseCase(fake).snapshot(serial).shouldBeInstanceOf<LayoutCapture.Failed>().reason shouldBe "Could not take the screenshot."
    }

    @Test
    fun `the screen alone carries the screenshot and the density, without the tree`() = runTest {
        val screen = CaptureLayoutUseCase(transport(mutableListOf())).screen(serial).shouldBeInstanceOf<LayoutCapture.Captured<*>>().value as ScreenImage

        screen.png.toList() shouldBe listOf<Byte>(1, 2, 3)
        screen.densityDpi shouldBe 440
    }
}
