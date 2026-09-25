package dev.acme.adbtoolbox.intellij.deviceactions

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsUseCase
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.application.deviceactions.OpenShellUseCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.deviceactions.FakeTerminalLauncher
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Connects task 016's [DeviceActionsViewModel] to task 015's already-registered
 * [DeviceFactsPanel]: mounts [DeviceActionsCoordinator.view] into [DeviceFactsPanel.deviceActionsSlot],
 * the same seam [dev.acme.adbtoolbox.intellij.capture.CaptureCoordinatorTest] exercises for task
 * 019's Screenshot control.
 */
class DeviceActionsCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private fun coordinator(panel: DeviceFactsPanel): DeviceActionsCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = DeviceActionsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            deviceActionsUseCase = DeviceActionsUseCase(FakeAdbTransport()),
            openShellUseCase = OpenShellUseCase(
                FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Adb, emptyList())) },
                FakeTerminalLauncher(),
            ),
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
        return DeviceActionsCoordinator(deviceFactsPanel = panel, viewModel = viewModel, scope = scope, dispatchers = dispatchers)
    }

    fun `test construction mounts the device actions into the Device section`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        val coordinator = coordinator(panel)

        assertTrue(panel.deviceActionsSlot.components.contains(coordinator.view))
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val viewModel = DeviceActionsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            deviceActionsUseCase = DeviceActionsUseCase(FakeAdbTransport()),
            openShellUseCase = OpenShellUseCase(
                FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Adb, emptyList())) },
                FakeTerminalLauncher(),
            ),
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
        val coordinator = DeviceActionsCoordinator(panel, viewModel, scope, dispatchers)

        coordinator.dispose()

        assertFalse(scope.isActive)
    }
}
