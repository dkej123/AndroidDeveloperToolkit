package dev.acme.adbtoolbox.intellij.ui.deviceactions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsUseCase
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewState
import dev.acme.adbtoolbox.application.deviceactions.OpenShellUseCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.deviceactions.DeviceActionKind
import dev.acme.adbtoolbox.domain.deviceactions.FakeTerminalLauncher
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Exercises [DeviceActionsView.render] directly (constructed values, no real coroutine round trip
 * through this headless `:intellij:test` sandbox — the same pattern
 * [dev.acme.adbtoolbox.intellij.ui.capture.CaptureViewTest] documents) plus the real click ->
 * [DeviceActionsViewModel.handle] wiring, proving the view itself never constructs a command.
 */
class DeviceActionsViewTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    private fun onlineDevice(serial: String) = Device(
        serial = DeviceSerial.of(serial),
        state = DeviceConnectionState.Online,
    )

    private fun viewModel(
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
        selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
    ): DeviceActionsViewModel {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), "", "") })
        val toolLocator = FakeToolLocator {
            DiscoveryOutcome.Found(
                DiscoveredTool(ToolId.Adb, ToolSource.PathFallback, ToolExecutablePath.of("/opt/homebrew/bin/adb"), ToolVersion.of("1")),
            )
        }
        return DeviceActionsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            deviceActionsUseCase = DeviceActionsUseCase(transport),
            openShellUseCase = OpenShellUseCase(toolLocator, FakeTerminalLauncher()),
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
    }

    fun `test render enables every button only when the control policy is Enabled and nothing is busy`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = DeviceActionsView(vm, scope, dispatchers)

        view.render(DeviceActionsViewState(controlPolicy = ControlPolicy.Enabled, busyAction = null))
        assertTrue(view.rebootButton.isEnabled)
        assertTrue(view.openShellButton.isEnabled)
        assertTrue(view.wakeButton.isEnabled)

        view.render(DeviceActionsViewState(controlPolicy = ControlPolicy.Enabled, busyAction = DeviceActionKind.Reboot))
        assertFalse(view.rebootButton.isEnabled)
        assertFalse(view.openShellButton.isEnabled)
        assertFalse(view.wakeButton.isEnabled)

        view.dispose()
        assertFalse(scope.isActive)
    }

    fun `test disabled buttons gain the supplied disabled reason, and re-enabling clears it`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = DeviceActionsView(vm, scope, dispatchers)

        view.render(
            DeviceActionsViewState(
                controlPolicy = ControlPolicy.Disabled(dev.acme.adbtoolbox.domain.device.DeviceCommandContext.Disabled.NoDeviceSelected),
                busyAction = null,
            ),
        )
        assertTrue(view.rebootButton.toolTipText.endsWith("Connect a device to use this"))
        assertTrue(view.openShellButton.toolTipText.endsWith("Connect a device to use this"))
        assertTrue(view.wakeButton.toolTipText.endsWith("Connect a device to use this"))

        view.render(DeviceActionsViewState(controlPolicy = ControlPolicy.Enabled, busyAction = null))

        assertEquals("Reboot the selected device", view.rebootButton.toolTipText)
        assertEquals("Open an adb shell session in the IDE's Terminal", view.openShellButton.toolTipText)
        assertEquals("Wake the selected device", view.wakeButton.toolTipText)
        view.dispose()
    }

    fun `test clicking Reboot forwards a Reboot request through the real view model`() {
        val dispatchers = TestDispatchers()
        val vmScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, dispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = DeviceActionsView(vm, viewScope, dispatchers)
        viewScope.cancel()

        assertNull(vm.state.value.busyAction)
        view.rebootButton.doClick()

        assertEquals(DeviceActionKind.Reboot, vm.state.value.busyAction)
        vmScope.cancel()
    }
}
