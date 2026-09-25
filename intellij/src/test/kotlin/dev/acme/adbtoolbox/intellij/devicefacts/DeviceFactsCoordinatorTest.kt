package dev.acme.adbtoolbox.intellij.devicefacts

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewModel
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.application.devicefacts.LoadDeviceFactsUseCase
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactId
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsSnapshot
import dev.acme.adbtoolbox.domain.devicefacts.FakeClipboardPort
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Connects task 015's [DeviceFactsViewModel] to task 010's [AdbToolboxHostPanel] via
 * [DeviceFactsCoordinator]. Kept as a [BasePlatformTestCase] like every other test in this module.
 * Exercises [DeviceFactsCoordinator.render] directly against constructed [DeviceFactsViewState]
 * values — the same "invoke the handler directly, don't depend on a real coroutine round trip"
 * pattern [dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinatorTest] documents for this
 * headless sandbox.
 */
class DeviceFactsCoordinatorTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private fun coordinator(host: AdbToolboxHostPanel): DeviceFactsCoordinator {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val viewModel = DeviceFactsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            loadDeviceFacts = LoadDeviceFactsUseCase(FakeAdbTransport()),
            clipboard = FakeClipboardPort(),
        )
        return DeviceFactsCoordinator(host, viewModel, scope, dispatchers)
    }

    fun `test construction registers the Device view into the host's active view host`() {
        val host = AdbToolboxHostPanel()

        val coordinator = coordinator(host)

        assertTrue(host.activeViewHost.isRegistered(ViewId.Device.routeKey))
        assertSame(coordinator.panel, host.activeViewHost.componentFor(ViewId.Device.routeKey))
        coordinator.dispose()
    }

    fun `test registering twice does not recreate the panel`() {
        val host = AdbToolboxHostPanel()
        val first = coordinator(host)

        val second = coordinator(host)

        assertSame(first.panel, second.panel)
        first.dispose()
        second.dispose()
    }

    fun `test rendering a Connected state updates the panel and enables Copy report`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)
        val snapshot = DeviceFactsSnapshot(
            serial = DeviceSerial.of("R58N90ABCDE"),
            facts = DeviceFactId.entries.associateWith { DeviceFactState.Unavailable("n/a") },
        )

        coordinator.render(DeviceFactsViewState.Connected(snapshot))

        assertTrue(coordinator.panel.copyReportButton.isEnabled)
        coordinator.dispose()
    }

    fun `test rendering NoDevice disables Copy report`() {
        val host = AdbToolboxHostPanel()
        val coordinator = coordinator(host)

        coordinator.render(DeviceFactsViewState.NoDevice)

        assertFalse(coordinator.panel.copyReportButton.isEnabled)
        coordinator.dispose()
    }

    fun `test disposing the coordinator cancels its scope`() {
        val host = AdbToolboxHostPanel()
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val viewModel = DeviceFactsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            loadDeviceFacts = LoadDeviceFactsUseCase(FakeAdbTransport()),
            clipboard = FakeClipboardPort(),
        )
        val coordinator = DeviceFactsCoordinator(host, viewModel, scope, dispatchers)

        coordinator.dispose()

        assertFalse(scope.isActive)
    }

    fun `test the coordinator wires up against a real view model without a construction-time crash`() {
        val host = AdbToolboxHostPanel()

        val coordinator = coordinator(host)

        assertNotNull(coordinator.panel)
        coordinator.dispose()
    }
}
