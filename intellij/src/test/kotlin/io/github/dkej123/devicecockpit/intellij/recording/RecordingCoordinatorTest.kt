package io.github.dkej123.devicecockpit.intellij.recording

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.application.recording.RecordingSessionManager
import io.github.dkej123.devicecockpit.application.recording.RecordingViewModel
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.capture.FakeCaptureDestination
import io.github.dkej123.devicecockpit.domain.capture.FileNamePolicy
import io.github.dkej123.devicecockpit.domain.capture.RevealInFileManager
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.github.dkej123.devicecockpit.domain.time.FakeMonotonicClock
import io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Connects task 020's [RecordingViewModel] to task 015's already-registered [DeviceFactsPanel]:
 * mounts [RecordingCoordinator.view] into [DeviceFactsPanel.captureSlot] — the same seam
 * [io.github.dkej123.devicecockpit.intellij.capture.CaptureCoordinatorTest] and
 * [io.github.dkej123.devicecockpit.intellij.mirroring.MirroringCoordinatorTest] exercise for their own
 * Device-view controls — rather than registering a competing
 * [io.github.dkej123.devicecockpit.intellij.host.FeatureViewHost] route.
 */
class RecordingCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private class NeverEndingTransport : AdbTransport {
        override suspend fun executeText(request: AdbRequest): AdbTextResult =
            AdbTextResult(AdbOutcome.Completed(0), "", "")

        override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = flow { awaitCancellation() }

        override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
    }

    private fun viewModel(scope: CoroutineScope, dispatchers: DispatcherProvider): RecordingViewModel {
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
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            sessionManager = sessionManager,
            revealInFileManager = RevealInFileManager { },
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
    }

    private fun coordinator(panel: DeviceFactsPanel): RecordingCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        return RecordingCoordinator(deviceFactsPanel = panel, viewModel = viewModel(scope, dispatchers), scope = scope, dispatchers = dispatchers)
    }

    fun `test construction mounts the Record button into the Screen toolbar and its status row below`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        val coordinator = coordinator(panel)

        assertTrue(panel.screenToolbar.components.contains(coordinator.view))
        assertTrue(panel.screenStatusSlot.components.contains(coordinator.view.statusRow))
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val coordinator = RecordingCoordinator(panel, viewModel(scope, dispatchers), scope, dispatchers)

        coordinator.dispose()

        assertFalse(scope.isActive)
    }
}
