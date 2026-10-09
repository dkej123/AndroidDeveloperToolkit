package io.github.dkej123.devicecockpit.application.capture

import io.github.dkej123.devicecockpit.domain.adb.AdbBinaryScript
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.capture.FakeCaptureDestination
import io.github.dkej123.devicecockpit.domain.capture.FileNamePolicy
import io.github.dkej123.devicecockpit.domain.capture.ImageClipboard
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CaptureClipboardTest {
    private val serial = DeviceSerial.of("emulator-5554")
    private val names = FileNamePolicy { "screen.png" }
    private val copied = mutableListOf<List<Byte>>()

    private fun useCase(outcome: AdbOutcome = AdbOutcome.Completed(0), enabled: Boolean = true, accepts: Boolean = true) = CaptureScreenshotUseCase(
        FakeAdbTransport(binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1, 2), byteArrayOf(3)), outcome) }),
        FakeCaptureDestination(),
        names,
        clipboard = ImageClipboard { png -> copied += png.toList(); accepts },
        copyToClipboard = { enabled },
    )

    @Test
    fun `a saved screenshot is also copied, whole`() = runTest {
        useCase().capture(serial).shouldBeInstanceOf<CaptureScreenshotResult.Success>().copiedToClipboard shouldBe true
        copied.single() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `the setting turns copying off`() = runTest {
        useCase(enabled = false).capture(serial).shouldBeInstanceOf<CaptureScreenshotResult.Success>().copiedToClipboard shouldBe false
        copied shouldBe emptyList()
    }

    @Test
    fun `a refused clipboard never fails the save, a failed capture copies nothing`() = runTest {
        useCase(accepts = false).capture(serial).shouldBeInstanceOf<CaptureScreenshotResult.Success>().copiedToClipboard shouldBe false
        copied.clear()
        useCase(outcome = AdbOutcome.TimedOut).capture(serial).shouldBeInstanceOf<CaptureScreenshotResult.Failure>()
        copied shouldBe emptyList()
    }
}
