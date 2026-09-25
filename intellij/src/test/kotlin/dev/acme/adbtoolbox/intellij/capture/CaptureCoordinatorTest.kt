package dev.acme.adbtoolbox.intellij.capture

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.capture.CaptureScreenshotUseCase
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Connects task 019's [CaptureViewModel] to task 015's already-registered [DeviceFactsPanel]: mounts
 * [CaptureCoordinator.view] into [DeviceFactsPanel.captureSlot] rather than registering a competing
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] route for [dev.acme.adbtoolbox.domain.nav.ViewId.Device]
 * (only one component may ever be registered per route key). Kept as a [BasePlatformTestCase] like
 * every other `:intellij` coordinator test in this module.
 */
class CaptureCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private fun coordinator(panel: DeviceFactsPanel): CaptureCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = CaptureViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            captureScreenshotUseCase = CaptureScreenshotUseCase(
                adbTransport = FakeAdbTransport(),
                captureDestination = FakeCaptureDestination(),
                fileNamePolicy = FileNamePolicy { "screen.png" },
            ),
            revealInFileManager = RevealInFileManager {},
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
        return CaptureCoordinator(deviceFactsPanel = panel, viewModel = viewModel, scope = scope, dispatchers = dispatchers)
    }

    fun `test construction mounts the screenshot control into the Capture section`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        val coordinator = coordinator(panel)

        assertTrue(panel.captureSlot.components.contains(coordinator.view))
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = CaptureViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            captureScreenshotUseCase = CaptureScreenshotUseCase(
                adbTransport = FakeAdbTransport(),
                captureDestination = FakeCaptureDestination(),
                fileNamePolicy = FileNamePolicy { "screen.png" },
            ),
            revealInFileManager = RevealInFileManager {},
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
        val coordinator = CaptureCoordinator(panel, viewModel, scope, dispatchers)

        coordinator.dispose()

        assertFalse(scope.isActive)
    }
}
