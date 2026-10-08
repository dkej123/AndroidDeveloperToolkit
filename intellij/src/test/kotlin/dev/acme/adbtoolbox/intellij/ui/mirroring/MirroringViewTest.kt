package dev.acme.adbtoolbox.intellij.ui.mirroring

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.mirroring.MirroringPresentationState
import dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringViewState
import dev.acme.adbtoolbox.application.mirroring.ScrcpyAvailability
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
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
import dev.acme.adbtoolbox.intellij.ui.common.HeldDispatcher
import dev.acme.adbtoolbox.intellij.ui.common.HeldDispatchers
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
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
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
        assertEquals("Start mirroring", view.toggleButton.accessibleContext.accessibleName)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle))
        assertTrue(view.toggleButton.isEnabled)
        assertTrue(view.optionsButton.isEnabled)
        assertSame(view.mirrorButton, view.toggleButton)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Running))
        assertTrue(view.toggleButton.isEnabled)
        assertEquals("Stop", view.toggleButton.text)
        assertTrue(view.statusRow.isVisible)
        assertEquals("Mirroring · Running", view.runningLabel.text)
        // The Mirror button itself turns teal and stops the session on click (design §3 "Screen").
        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.ToolButtonTone.BRAND, view.mirrorButton.tone)
        assertEquals("Stop mirroring", view.mirrorButton.accessibleContext.accessibleName)

        view.dispose()
        assertFalse(scope.isActive)
    }

    fun `test idle state shows only the split button, without help text or the running row`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = MirroringView(vm, scope, dispatchers)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle))

        assertEquals("Start mirroring", view.toggleButton.accessibleContext.accessibleName)
        assertEquals(listOf(view.mirrorButton, view.optionsButton), view.toolbarPart.components.toList())
        assertFalse(view.statusRow.isVisible)
        assertFalse(view.helpLabel.isVisible)
        view.dispose()
    }

    fun `test clicking the toggle button forwards a Toggle intent through the real view model`() {
        val dispatchers = TestDispatchers()
        // The view model's own coroutines are held, so only the click's synchronous effect is observed.
        val vmScope = CoroutineScope(SupervisorJob() + HeldDispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, HeldDispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = MirroringView(vm, viewScope, dispatchers)
        viewScope.cancel()

        assertEquals(MirroringPresentationState.Idle, vm.state.value.presentationState)
        view.toggleButton.doClick()

        assertEquals(MirroringPresentationState.Starting, vm.state.value.presentationState)
        vmScope.cancel()
    }

    fun `test the options icon button exposes an accessible name derived from its tooltip`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = MirroringView(vm, scope, dispatchers)

        assertEquals("Mirroring options", view.optionsButton.getAccessibleContext().accessibleName)
        view.dispose()
    }

    fun `test a disabled control policy names the blocker in the start and options tooltips`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = MirroringView(vm, scope, dispatchers)

        view.render(
            MirroringViewState(
                controlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.NoDeviceSelected),
                presentationState = MirroringPresentationState.Unavailable,
            ),
        )

        assertTrue(view.toggleButton.toolTipText.endsWith("Connect a device to use this"))
        assertTrue(view.optionsButton.toolTipText.endsWith("Connect a device to use this"))

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle))
        // The chord comes from the active keymap (ShortcutHintsTest), never a hardcoded "⇧⌘M".
        assertTrue(view.toggleButton.toolTipText.startsWith("Start mirroring — opens a scrcpy window"))
        if (!com.intellij.openapi.util.SystemInfo.isMac) assertFalse('⌘' in view.toggleButton.toolTipText)
        assertEquals("Mirroring options — bitrate, resolution, stay awake, show touches", view.optionsButton.toolTipText)
        view.dispose()
    }

    fun `test the options caret shows and hides the inline options panel without touching the session`() {
        val dispatchers = TestDispatchers()
        val vmScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, dispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val optionsViewModel = dev.acme.adbtoolbox.application.mirroring.MirroringOptionsViewModel(
            viewScope,
            dispatchers,
            dev.acme.adbtoolbox.application.mirroring.MirroringOptionsUseCase(
                dev.acme.adbtoolbox.domain.mirroring.FakeMirroringOptionsRepository(),
            ),
        )
        val panel = MirroringOptionsPanel(optionsViewModel, viewScope, dispatchers)
        val view = MirroringView(vm, viewScope, dispatchers, optionsPanel = panel)
        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle))
        assertFalse(panel.isVisible)

        view.optionsButton.doClick()
        assertTrue(panel.isVisible)
        assertTrue(view.optionsButton.isOpen)

        view.optionsButton.doClick()
        assertFalse(panel.isVisible)
        assertEquals(MirroringPresentationState.Idle, vm.state.value.presentationState)
        viewScope.cancel()
        vmScope.cancel()
    }

    fun `test a missing scrcpy greys out Start and options and explains how to install or configure it`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = MirroringView(vm, scope, dispatchers)
        val missing = ScrcpyAvailability.Missing(
            reason = "scrcpy is not installed, or not on PATH.",
            installCommand = "brew install scrcpy",
            configuredPathInvalid = false,
        )

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle, scrcpy = missing))

        assertFalse(view.toggleButton.isEnabled)
        // Options only configure scrcpy, so they are useless until it is installed (user request 2026-10-01).
        assertFalse(view.optionsButton.isEnabled)
        assertEquals(missing.fixHint(), view.optionsButton.toolTipText)
        assertEquals(missing.fixHint(), view.toggleButton.toolTipText)
        assertEquals(missing.fixHint(), view.helpLabel.text)
        assertTrue(view.scrcpyFixRow.isVisible)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle, scrcpy = ScrcpyAvailability.Available(ToolVersion.of("3.1"))))

        assertTrue(view.toggleButton.isEnabled)
        assertTrue(view.optionsButton.isEnabled)
        assertFalse(view.scrcpyFixRow.isVisible)
        scope.cancel()
    }

    fun `test the fix links open Settings, re-check scrcpy and open the install guide`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        var guideOpened = false
        val view = MirroringView(vm, scope, dispatchers, openInstallGuide = { guideOpened = true })

        view.installGuideLink.doClick()
        assertTrue(guideOpened)
        assertEquals(listOf("Open Settings", "Check again", "Install guide"), listOf(view.openSettingsLink, view.recheckLink, view.installGuideLink).map { it.text })
        scope.cancel()
    }

    fun `test re-checking shows progress instead of the stale message`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = MirroringView(vm, scope, dispatchers)

        view.render(MirroringViewState(controlPolicy = ControlPolicy.Enabled, presentationState = MirroringPresentationState.Idle, scrcpy = ScrcpyAvailability.Checking))

        assertTrue(view.toggleButton.isEnabled)
        assertTrue(view.optionsButton.isEnabled)
        assertFalse(view.scrcpyFixRow.isVisible)
        scope.cancel()
    }
}
