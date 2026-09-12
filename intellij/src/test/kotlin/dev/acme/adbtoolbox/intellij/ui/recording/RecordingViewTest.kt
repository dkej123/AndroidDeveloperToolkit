package dev.acme.adbtoolbox.intellij.ui.recording

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.recording.RecordingPresentationState
import dev.acme.adbtoolbox.application.recording.RecordingSessionManager
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.application.recording.RecordingViewState
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.time.FakeMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Exercises [RecordingView.render] directly (constructed values, no real coroutine round trip
 * through this headless `:intellij:test` sandbox — the same pattern
 * [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringViewTest] documents) plus the real
 * click -> [RecordingViewModel.handle] wiring, proving the view itself never decides start-vs-stop.
 */
class RecordingViewTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private class NeverEndingTransport : AdbTransport {
        override suspend fun executeText(request: AdbRequest): AdbTextResult =
            AdbTextResult(AdbOutcome.Completed(0), "", "")

        override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = flow { awaitCancellation() }

        override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
    }

    private fun onlineDevice(serial: String) = Device(serial = DeviceSerial.of(serial), state = DeviceConnectionState.Online)

    private fun viewModel(
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
        selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
    ): RecordingViewModel {
        val sessionManager = RecordingSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            adbTransport = NeverEndingTransport(),
            captureDestination = FakeCaptureDestination(),
            fileNamePolicy = FileNamePolicy { "screen-1.mp4" },
            monotonicClock = FakeMonotonicClock(),
        )
        return RecordingViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            sessionManager = sessionManager,
            revealInFileManager = RevealInFileManager { },
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
    }

    fun `test render enables the button only when the control policy is Enabled and shows the state-appropriate label`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = RecordingView(vm, scope, dispatchers)

        view.render(
            RecordingViewState(
                controlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.NoDeviceSelected),
                presentationState = RecordingPresentationState.Unavailable,
            ),
        )
        assertFalse(view.toggleButton.isEnabled)
        assertEquals("Record", view.toggleButton.text)

        view.render(RecordingViewState(controlPolicy = ControlPolicy.Enabled, presentationState = RecordingPresentationState.Idle))
        assertTrue(view.toggleButton.isEnabled)
        assertEquals("Record", view.toggleButton.text)

        view.render(
            RecordingViewState(
                controlPolicy = ControlPolicy.Enabled,
                presentationState = RecordingPresentationState.Recording,
                elapsedLabel = "00:42",
            ),
        )
        assertTrue(view.toggleButton.isEnabled)
        assertEquals("Stop & save", view.toggleButton.text)
        assertEquals("Recording · 00:42", view.statusLabel.text)

        view.render(RecordingViewState(controlPolicy = ControlPolicy.Enabled, presentationState = RecordingPresentationState.Stopping))
        assertFalse(view.toggleButton.isEnabled)
        assertEquals("Stopping…", view.toggleButton.text)

        view.render(RecordingViewState(controlPolicy = ControlPolicy.Enabled, presentationState = RecordingPresentationState.Pulling))
        assertFalse(view.toggleButton.isEnabled)
        assertEquals("Saving…", view.toggleButton.text)

        view.dispose()
        assertFalse(scope.isActive)
    }

    fun `test the status label is empty outside the Recording state`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = RecordingView(vm, scope, dispatchers)

        view.render(RecordingViewState(controlPolicy = ControlPolicy.Enabled, presentationState = RecordingPresentationState.Idle))

        assertEquals("", view.statusLabel.text)
        view.dispose()
    }

    fun `test clicking the toggle button forwards a Toggle intent through the real view model`() {
        val dispatchers = TestDispatchers()
        val vmScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, dispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = RecordingView(vm, viewScope, dispatchers)
        viewScope.cancel()

        assertEquals(RecordingPresentationState.Idle, vm.state.value.presentationState)
        view.toggleButton.doClick()

        assertEquals(RecordingPresentationState.Starting, vm.state.value.presentationState)
        vmScope.cancel()
    }
}
