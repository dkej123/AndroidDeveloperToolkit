package dev.acme.adbtoolbox.intellij.ui.mirroring

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.mirroring.MirroringPresentationState
import dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringViewState
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.process.FakeProcessExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Exercises [MirroringView.render] directly (constructed values, no real coroutine round trip
 * through this headless `:intellij:test` sandbox — the same pattern
 * [dev.acme.adbtoolbox.intellij.ui.deviceactions.DeviceActionsViewTest] documents) plus the real
 * click -> [MirroringViewModel.handle] wiring, proving the view itself never decides start-vs-stop.
 */
class MirroringViewTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private fun onlineDevice(serial: String) = Device(serial = DeviceSerial.of(serial), state = DeviceConnectionState.Online)

    private fun viewModel(
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
        selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
    ): MirroringViewModel {
        val sessionManager = MirroringSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            toolLocator = FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Scrcpy, emptyList())) },
            processExecutor = FakeProcessExecutor { emptyList() },
        )
        return MirroringViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            sessionManager = sessionManager,
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
            navigation = NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device),
        )
    }

    fun `test render enables the button only when the control policy is Enabled and shows the state-appropriate label`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = MirroringView(vm, scope, dispatchers)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.NoDeviceSelected), presentationState = MirroringPresentationState.Unavailable))
        assertFalse(view.toggleButton.isEnabled)
        assertFalse(view.optionsButton.isEnabled)
        assertEquals("Start mirroring", view.toggleButton.text)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle))
        assertTrue(view.toggleButton.isEnabled)
        assertTrue(view.optionsButton.isEnabled)
        assertEquals("Start mirroring", view.toggleButton.text)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Running))
        assertTrue(view.toggleButton.isEnabled)
        assertEquals("Stop", view.toggleButton.text)
        assertTrue(view.runningBanner.isVisible)
        assertEquals("Mirroring · Running", view.runningLabel.text)

        view.dispose()
        assertFalse(scope.isActive)
    }

    fun `test idle state uses supplied controls and help copy and hides the running banner`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = MirroringView(vm, scope, dispatchers)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle))

        assertEquals("Start mirroring", view.toggleButton.text)
        assertEquals("", view.optionsButton.text)
        assertNotNull(view.optionsButton.icon)
        assertFalse(view.runningBanner.isVisible)
        assertEquals(
            "Launches Genymobile scrcpy. Turn on “stay awake” and “show touches” in options.",
            view.helpLabel.text,
        )
        view.dispose()
    }

    fun `test clicking the toggle button forwards a Toggle intent through the real view model`() {
        val dispatchers = TestDispatchers()
        val vmScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, dispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = MirroringView(vm, viewScope, dispatchers)
        viewScope.cancel()

        assertEquals(MirroringPresentationState.Idle, vm.state.value.presentationState)
        view.toggleButton.doClick()

        assertEquals(MirroringPresentationState.Starting, vm.state.value.presentationState)
        vmScope.cancel()
    }

    fun `test clicking the options button forwards to the injected openOptions callback without touching the view model`() {
        val dispatchers = TestDispatchers()
        val vmScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, dispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        var openCount = 0
        val view = MirroringView(vm, viewScope, dispatchers, openOptions = { openCount++ })
        viewScope.cancel()

        view.optionsButton.doClick()

        assertEquals(1, openCount)
        assertEquals(MirroringPresentationState.Idle, vm.state.value.presentationState)
        vmScope.cancel()
    }
}
