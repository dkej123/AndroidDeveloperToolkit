@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.capture

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val FIXED_CLOCK = object : kotlinx.datetime.Clock {
    override fun now(): Instant = Instant.parse("2026-09-02T10:15:30Z")
}
private val FIXED_FILE_NAME = FileNamePolicy { "screen-20260902-101530.png" }
private val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

private fun onlineDevice(serial: String) = Device(
    serial = DeviceSerial.of(serial),
    state = DeviceConnectionState.Online,
)

class CaptureViewModelTest {

    private class Harness(
        binaryScript: (dev.acme.adbtoolbox.domain.adb.AdbRequest) -> AdbBinaryScript = {
            AdbBinaryScript(listOf(PNG_BYTES), AdbOutcome.Completed(0))
        },
    ) {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = TestDispatcherProviderFixture(dispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val transport = FakeAdbTransport(binaryScript = binaryScript)
        val destination = FakeCaptureDestination()
        val useCase = CaptureScreenshotUseCase(transport, destination, FIXED_FILE_NAME, FIXED_CLOCK)
        val revealed = mutableListOf<CaptureLocation>()
        val revealInFileManager = RevealInFileManager { location -> revealed += location }
        val feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val viewModel = CaptureViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            captureScreenshotUseCase = useCase,
            revealInFileManager = revealInFileManager,
            feedback = feedback,
            clock = FIXED_CLOCK,
        )
    }

    @Test
    fun `initial control policy mirrors the current selected-device state`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))
        val vm = h.viewModel
        h.scope.runCurrent()

        vm.state.value.controlPolicy shouldBe ControlPolicy.Enabled
    }

    @Test
    fun `capturing with no eligible device posts a warning toast and issues no ADB request`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.None

        h.viewModel.handle(CaptureIntent.CaptureScreenshot)
        h.scope.runCurrent()

        h.transport.binaryRequests shouldBe emptyList()
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Warning
    }

    @Test
    fun `a successful capture updates lastCapture and posts a success toast with a working reveal action`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(CaptureIntent.CaptureScreenshot)
        h.scope.runCurrent()

        val state = h.viewModel.state.value
        state.isCapturing shouldBe false
        state.lastCapture.shouldBeInstanceOf<CaptureLocation>()

        val toast = h.feedback.state.value.toasts.single()
        toast.severity shouldBe FeedbackSeverity.Success
        toast.action?.invoke()
        h.revealed shouldBe listOf(state.lastCapture)
    }

    @Test
    fun `a failed capture posts an error toast and never sets lastCapture`() = runTest {
        val h = Harness(binaryScript = { AdbBinaryScript(emptyList(), AdbOutcome.Completed(0)) })
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(CaptureIntent.CaptureScreenshot)
        h.scope.runCurrent()

        h.viewModel.state.value.lastCapture shouldBe null
        h.feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Error
    }

    @Test
    fun `RevealLastCapture is a no-op until a capture has actually succeeded`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(CaptureIntent.RevealLastCapture)
        h.scope.runCurrent()

        h.revealed shouldBe emptyList()
    }

    @Test
    fun `changing the selected device after a capture starts does not reattribute the in-flight request`() = runTest {
        val h = Harness()
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-5554"))

        h.viewModel.handle(CaptureIntent.CaptureScreenshot)
        // Device selection changes before the launched coroutine body has run.
        h.selectedDeviceState.value = SelectedDeviceState.Online(onlineDevice("emulator-9999"))
        h.scope.runCurrent()

        val request = h.transport.binaryRequests.single() as AdbDeviceRequest
        request.serial shouldBe DeviceSerial.of("emulator-5554")
    }
}
