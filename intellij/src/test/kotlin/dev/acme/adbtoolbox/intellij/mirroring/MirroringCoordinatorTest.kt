package dev.acme.adbtoolbox.intellij.mirroring

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.process.FakeProcessExecutor
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Connects task 018's [MirroringViewModel] to task 015's already-registered [DeviceFactsPanel]:
 * mounts [MirroringCoordinator.view] into [DeviceFactsPanel.mirroringSlot] — the same seam
 * [dev.acme.adbtoolbox.intellij.capture.CaptureCoordinatorTest] and
 * [dev.acme.adbtoolbox.intellij.deviceactions.DeviceActionsCoordinatorTest] exercise for their own
 * Device-view controls — rather than registering a competing
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] route.
 */
class MirroringCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private fun viewModel(scope: CoroutineScope, dispatchers: DispatcherProvider): MirroringViewModel {
        val sessionManager = MirroringSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            toolLocator = FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, emptyList())) },
            processExecutor = FakeProcessExecutor { emptyList() },
        )
        return MirroringViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            sessionManager = sessionManager,
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
            navigation = NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device),
        )
    }

    private fun coordinator(panel: DeviceFactsPanel): MirroringCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        return MirroringCoordinator(deviceFactsPanel = panel, viewModel = viewModel(scope, dispatchers), scope = scope, dispatchers = dispatchers)
    }

    fun `test construction mounts the mirroring control into its supplied section`() {
        val panel = DeviceFactsPanel(onCopyReport = {})

        val coordinator = coordinator(panel)

        assertTrue(panel.mirroringSlot.components.contains(coordinator.view))
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope`() {
        val panel = DeviceFactsPanel(onCopyReport = {})
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val coordinator = MirroringCoordinator(panel, viewModel(scope, dispatchers), scope, dispatchers)

        coordinator.dispose()

        assertFalse(scope.isActive)
    }
}
