package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.capture.CaptureScreenshotUseCase
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewModel
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsUseCase
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.application.deviceactions.OpenShellUseCase
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewModel
import dev.acme.adbtoolbox.application.devicefacts.LoadDeviceFactsUseCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.FakeDeviceListRefresher
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.deviceactions.FakeTerminalLauncher
import dev.acme.adbtoolbox.domain.devicefacts.FakeClipboardPort
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.process.FakeProcessExecutor
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.dispatch.IdeDispatcherProvider
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Task 010 wires the neutral [AdbToolboxHostPanel] (named slots for device context, navigation,
 * active view, and feedback) into the ToolWindow content this panel mounts, replacing the bare
 * placeholder from task 007. Task 013 replaces the feedback slot's `ShellViewModel` status-text
 * stand-in with the real [FeedbackViewModel]-driven feedback/status/toast infrastructure. Task 011
 * mounts the device context bar into the device context slot. Kept as a [BasePlatformTestCase]
 * like every other test in this module.
 */
class AdbToolboxToolWindowPanelTest : BasePlatformTestCase() {

    // IdeDispatcherProvider() touches ApplicationManager.getApplication() at construction, which
    // is not yet initialized during JUnit-3-style field initialization (before setUp() runs) — so
    // it is constructed fresh per test method instead of as a class-level val, matching
    // AdbToolboxProjectServiceTest's pattern of only touching platform services inside test bodies.
    private fun dispatchers(): DispatcherProvider = IdeDispatcherProvider()

    private fun navigationViewModel(scope: CoroutineScope, dispatchers: DispatcherProvider): NavigationViewModel =
        NavigationViewModel(scope, dispatchers, FakeNavigationPersistence(), ViewId.Device)

    private class Harness(dispatchers: DispatcherProvider) {
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val navigationScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val feedbackScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val deviceFactsScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val deviceBarScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val captureScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val deviceActionsScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val mirroringScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    }

    private fun captureViewModel(scope: CoroutineScope, dispatchers: DispatcherProvider, feedback: FeedbackViewModel): CaptureViewModel =
        CaptureViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            captureScreenshotUseCase = CaptureScreenshotUseCase(
                adbTransport = FakeAdbTransport(),
                captureDestination = FakeCaptureDestination(),
                fileNamePolicy = FileNamePolicy { "screen.png" },
            ),
            revealInFileManager = RevealInFileManager {},
            feedback = feedback,
        )

    private fun deviceActionsViewModel(scope: CoroutineScope, dispatchers: DispatcherProvider, feedback: FeedbackViewModel): DeviceActionsViewModel =
        DeviceActionsViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            deviceActionsUseCase = DeviceActionsUseCase(FakeAdbTransport()),
            openShellUseCase = OpenShellUseCase(
                FakeToolLocator { DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(ToolId.Adb, emptyList())) },
                FakeTerminalLauncher(),
            ),
            feedback = feedback,
        )

    private fun mirroringViewModel(
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
        feedback: FeedbackViewModel,
        navigation: NavigationViewModel,
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
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            sessionManager = sessionManager,
            feedback = feedback,
            navigation = navigation,
        )
    }

    private fun deviceBarViewModel(scope: CoroutineScope, dispatchers: DispatcherProvider): DeviceBarViewModel {
        val repository = FakeDeviceRepository()
        val selectedDeviceViewModel = SelectedDeviceViewModel(
            scope = scope,
            dispatchers = dispatchers,
            deviceRepository = repository,
            persistence = FakeDeviceSelectionPersistence(),
        )
        return DeviceBarViewModel(
            scope = scope,
            dispatchers = dispatchers,
            deviceRepository = repository,
            selectedDeviceViewModel = selectedDeviceViewModel,
            refresher = FakeDeviceListRefresher(),
        )
    }

    private fun panel(dispatchers: DispatcherProvider, harness: Harness): AdbToolboxToolWindowPanel {
        val feedbackViewModel = FeedbackViewModel(harness.feedbackScope, dispatchers)
        val navigationVm = navigationViewModel(harness.navigationScope, dispatchers)
        return AdbToolboxToolWindowPanel(
            viewModel = ShellViewModel(harness.scope, dispatchers),
            dispatchers = dispatchers,
            scope = harness.scope,
            navigationViewModel = navigationVm,
            navigationScope = harness.navigationScope,
            feedbackViewModel = feedbackViewModel,
            feedbackScope = harness.feedbackScope,
            deviceFactsViewModel = DeviceFactsViewModel(
                scope = harness.deviceFactsScope,
                dispatchers = dispatchers,
                selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
                loadDeviceFacts = LoadDeviceFactsUseCase(FakeAdbTransport()),
                clipboard = FakeClipboardPort(),
            ),
            deviceFactsScope = harness.deviceFactsScope,
            deviceBarViewModel = deviceBarViewModel(harness.deviceBarScope, dispatchers),
            deviceBarScope = harness.deviceBarScope,
            captureViewModel = captureViewModel(harness.captureScope, dispatchers, feedbackViewModel),
            captureScope = harness.captureScope,
            deviceActionsViewModel = deviceActionsViewModel(harness.deviceActionsScope, dispatchers, feedbackViewModel),
            deviceActionsScope = harness.deviceActionsScope,
            mirroringViewModel = mirroringViewModel(harness.mirroringScope, dispatchers, feedbackViewModel, navigationVm),
            mirroringScope = harness.mirroringScope,
        )
    }

    fun `test the panel mounts a host with the named slots`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertNotNull(panel.host.deviceContextSlot)
        assertNotNull(panel.host.navigationSlot)
        assertNotNull(panel.host.activeViewHost)
        assertNotNull(panel.host.feedbackSlot)

        panel.dispose()
    }

    fun `test the navigation rail is mounted into the navigation slot`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertTrue(panel.host.navigationSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test the device context bar panel is mounted into the device context slot`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertTrue(panel.host.deviceContextSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test the feedback status panel is mounted into the feedback slot`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertTrue(panel.host.feedbackSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test disposing the panel disposes its host and cancels the feedback scope too`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)
        val overlay = javax.swing.JLabel("toast")
        panel.host.overlays.show(overlay)

        panel.dispose()

        assertFalse(panel.host.overlays.isShowing(overlay))
        assertFalse(harness.scope.isActive)
        assertFalse(harness.navigationScope.isActive)
        assertFalse(harness.feedbackScope.isActive)
        assertFalse(harness.deviceFactsScope.isActive)
        assertFalse(harness.deviceBarScope.isActive)
        assertFalse(harness.captureScope.isActive)
        assertFalse(harness.deviceActionsScope.isActive)
        assertFalse(harness.mirroringScope.isActive)
    }

    fun `test the screenshot control is mounted into the Device view alongside device facts`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        val deviceView = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey)
        assertTrue(deviceView is DeviceFactsPanel)
        val actionsRow = (deviceView as DeviceFactsPanel).actionsRow
        assertTrue(actionsRow.componentCount > 1)

        panel.dispose()
    }

    fun `test the device-actions controls are mounted into the Device view alongside device facts and capture`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        val deviceView = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey)
        assertTrue(deviceView is DeviceFactsPanel)
        val actionsRow = (deviceView as DeviceFactsPanel).actionsRow
        assertTrue(actionsRow.componentCount > 2)

        panel.dispose()
    }

    fun `test the mirroring control is mounted into the Device view alongside the other action-row controls`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        val deviceView = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey)
        assertTrue(deviceView is DeviceFactsPanel)
        val actionsRow = (deviceView as DeviceFactsPanel).actionsRow
        assertTrue(actionsRow.componentCount > 3)

        panel.dispose()
    }
}
