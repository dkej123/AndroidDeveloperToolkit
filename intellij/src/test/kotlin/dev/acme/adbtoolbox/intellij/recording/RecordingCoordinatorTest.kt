package dev.acme.adbtoolbox.intellij.recording

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.recording.RecordingSessionManager
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.process.ByteSink
import dev.acme.adbtoolbox.domain.time.FakeMonotonicClock
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
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
 * [dev.acme.adbtoolbox.intellij.capture.CaptureCoordinatorTest] and
 * [dev.acme.adbtoolbox.intellij.mirroring.MirroringCoordinatorTest] exercise for their own
 * Device-view controls — rather than registering a competing
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] route.
 */
class RecordingCoordinatorTest : BasePlatformTestCase() {

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

    fun `test construction mounts the recording control into the Capture section`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        val coordinator = coordinator(panel)

        assertTrue(panel.captureSlot.components.contains(coordinator.view))
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
