package io.github.dkej123.devicecockpit.intellij.ui.deviceactions

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsUseCase
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewModel
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewState
import io.github.dkej123.devicecockpit.application.deviceactions.OpenShellUseCase
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy
import io.github.dkej123.devicecockpit.domain.deviceactions.DeviceActionKind
import io.github.dkej123.devicecockpit.domain.deviceactions.FakeTerminalLauncher
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveredTool
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.ToolExecutablePath
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.intellij.ui.common.HeldDispatcher
import io.github.dkej123.devicecockpit.intellij.ui.common.HeldDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Exercises [DeviceActionsView.render] directly (constructed values, no real coroutine round trip
 * through this headless `:intellij:test` sandbox — the same pattern
 * [io.github.dkej123.devicecockpit.intellij.ui.capture.CaptureViewTest] documents) plus the real click ->
 * [DeviceActionsViewModel.handle] wiring, proving the view itself never constructs a command.
 */
class DeviceActionsViewTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
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
                controlPolicy = ControlPolicy.Disabled(io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext.Disabled.NoDeviceSelected),
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
        // The view model's own coroutines are held, so only the click's synchronous effect is observed.
        val vmScope = CoroutineScope(SupervisorJob() + HeldDispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, HeldDispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = DeviceActionsView(vm, viewScope, dispatchers)
        viewScope.cancel()

        assertNull(vm.state.value.busyAction)
        view.rebootButton.doClick()

        assertEquals(DeviceActionKind.Reboot, vm.state.value.busyAction)
        vmScope.cancel()
    }
}
