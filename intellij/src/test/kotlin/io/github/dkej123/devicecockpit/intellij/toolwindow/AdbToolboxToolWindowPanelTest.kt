package io.github.dkej123.devicecockpit.intellij.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleViewModel
import io.github.dkej123.devicecockpit.application.apps.AppsViewModel
import io.github.dkej123.devicecockpit.application.apps.ClearDataUseCase
import io.github.dkej123.devicecockpit.application.apps.ClearDataViewModel
import io.github.dkej123.devicecockpit.application.apps.UninstallUseCase
import io.github.dkej123.devicecockpit.application.apps.UninstallViewModel
import io.github.dkej123.devicecockpit.application.apps.SelectedPackageViewModel
import io.github.dkej123.devicecockpit.application.capture.CaptureScreenshotUseCase
import io.github.dkej123.devicecockpit.application.capture.CaptureViewModel
import io.github.dkej123.devicecockpit.application.device.SelectedDeviceViewModel
import io.github.dkej123.devicecockpit.application.devicebar.DeviceBarViewModel
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsUseCase
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewModel
import io.github.dkej123.devicecockpit.application.deviceactions.OpenShellUseCase
import io.github.dkej123.devicecockpit.application.devicecontext.DeviceContextAggregator
import io.github.dkej123.devicecockpit.application.devicefacts.DeviceFactsViewModel
import io.github.dkej123.devicecockpit.application.devicefacts.LoadDeviceFactsUseCase
import io.github.dkej123.devicecockpit.application.display.QuickTogglesViewModel
import io.github.dkej123.devicecockpit.application.display.density.DensityOverrideTracker
import io.github.dkej123.devicecockpit.application.display.density.DensityUseCase
import io.github.dkej123.devicecockpit.application.display.density.DensityViewModel
import io.github.dkej123.devicecockpit.application.display.fontscale.FontScaleViewModel
import io.github.dkej123.devicecockpit.application.feedback.FeedbackViewModel
import io.github.dkej123.devicecockpit.application.mirroring.MirroringSessionManager
import io.github.dkej123.devicecockpit.application.mirroring.MirroringViewModel
import io.github.dkej123.devicecockpit.application.nav.NavigationViewModel
import io.github.dkej123.devicecockpit.application.network.ProxyController
import io.github.dkej123.devicecockpit.application.recording.RecordingSessionManager
import io.github.dkej123.devicecockpit.application.recording.RecordingViewModel
import io.github.dkej123.devicecockpit.application.shell.ShellViewModel
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.capture.FakeCaptureDestination
import io.github.dkej123.devicecockpit.domain.capture.FileNamePolicy
import io.github.dkej123.devicecockpit.domain.capture.RevealInFileManager
import io.github.dkej123.devicecockpit.domain.time.FakeMonotonicClock
import io.github.dkej123.devicecockpit.domain.device.FakeDeviceListRefresher
import io.github.dkej123.devicecockpit.domain.device.FakeDeviceRepository
import io.github.dkej123.devicecockpit.domain.device.FakeDeviceSelectionPersistence
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.toCommandContext
import io.github.dkej123.devicecockpit.domain.deviceactions.FakeTerminalLauncher
import io.github.dkej123.devicecockpit.domain.apps.FakeClearDataConfirmationPort
import io.github.dkej123.devicecockpit.domain.apps.FakeUninstallConfirmationPort
import io.github.dkej123.devicecockpit.domain.apps.FakeSelectedPackagePersistence
import io.github.dkej123.devicecockpit.domain.devicefacts.FakeClipboardPort
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.FakeToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.nav.FakeNavigationPersistence
import io.github.dkej123.devicecockpit.domain.nav.ViewId
import io.github.dkej123.devicecockpit.domain.network.FakeHostNetworkInfo
import io.github.dkej123.devicecockpit.domain.network.FakeNetworkRecentsPersistence
import io.github.dkej123.devicecockpit.domain.process.FakeProcessExecutor
import io.github.dkej123.devicecockpit.domain.packages.FakePackageRepository
import io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsPanel
import io.github.dkej123.devicecockpit.intellij.dispatch.IdeDispatcherProvider
import io.github.dkej123.devicecockpit.intellij.host.AdbToolboxHostPanel
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
        val recordingScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val appsScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val networkScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val displayScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val logcatScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    }

    private data class AppsModels(
        val apps: AppsViewModel,
        val lifecycle: AppLifecycleViewModel,
        val clearData: ClearDataViewModel,
        val uninstall: UninstallViewModel,
    )

    private fun appsModels(
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
        feedback: FeedbackViewModel,
    ): AppsModels {
        val transport = FakeAdbTransport()
        val deviceRepository = FakeDeviceRepository()
        val selectedDevice = SelectedDeviceViewModel(
            scope = scope,
            dispatchers = dispatchers,
            deviceRepository = deviceRepository,
            persistence = FakeDeviceSelectionPersistence(),
        )
        val selectedPackage = SelectedPackageViewModel(
            scope = scope,
            dispatchers = dispatchers,
            persistence = FakeSelectedPackagePersistence(),
        )
        val packageRepository = FakePackageRepository()
        val appsViewModel = AppsViewModel(scope, dispatchers, packageRepository, selectedDevice, selectedPackage)
        return AppsModels(
            apps = appsViewModel,
            lifecycle = AppLifecycleViewModel(
                scope,
                dispatchers,
                selectedDevice.state,
                selectedPackage.state,
                AppLifecycleUseCase(transport),
                feedback,
            ),
            clearData = ClearDataViewModel(
                scope = scope,
                dispatchers = dispatchers,
                selectedDeviceState = selectedDevice.state,
                selectedPackageState = selectedPackage.state,
                currentPackageScope = appsViewModel.currentPackageScope,
                clearDataUseCase = ClearDataUseCase(transport),
                confirmationPort = FakeClearDataConfirmationPort(),
                packageRepository = packageRepository,
                feedback = feedback,
            ),
            uninstall = UninstallViewModel(
                scope = scope,
                dispatchers = dispatchers,
                selectedDeviceState = selectedDevice.state,
                selectedPackageState = selectedPackage.state,
                currentPackageScope = appsViewModel.currentPackageScope,
                uninstallUseCase = UninstallUseCase(transport),
                confirmationPort = FakeUninstallConfirmationPort(),
                packageRepository = packageRepository,
                selectedPackageViewModel = selectedPackage,
                feedback = feedback,
            ),
        )
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

    private fun recordingViewModel(scope: CoroutineScope, dispatchers: DispatcherProvider, feedback: FeedbackViewModel): RecordingViewModel {
        val sessionManager = RecordingSessionManager(
            scope = scope,
            dispatchers = dispatchers,
            adbTransport = FakeAdbTransport(),
            captureDestination = FakeCaptureDestination(),
            fileNamePolicy = FileNamePolicy { "screen.mp4" },
            monotonicClock = FakeMonotonicClock(),
        )
        return RecordingViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            sessionManager = sessionManager,
            revealInFileManager = RevealInFileManager {},
            feedback = feedback,
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

    private fun proxyController(scope: CoroutineScope, dispatchers: DispatcherProvider): ProxyController =
        ProxyController(
            scope = scope,
            dispatchers = dispatchers,
            transport = FakeAdbTransport(),
            selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None),
            hostNetworkInfo = FakeHostNetworkInfo(),
            recentsPersistence = FakeNetworkRecentsPersistence(),
        )

    private fun logcatControlsController(
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
    ): io.github.dkej123.devicecockpit.application.logcat.LogcatControlsController {
        val transport = FakeAdbTransport()
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val selectedPackageState = MutableStateFlow<io.github.dkej123.devicecockpit.domain.apps.SelectedPackageState>(
            io.github.dkej123.devicecockpit.domain.apps.SelectedPackageState.None,
        )
        val sessionManager = io.github.dkej123.devicecockpit.application.logcat.LogcatSessionManager(
            scope, dispatchers, selectedDeviceState, transport,
        )
        val pidTracker = io.github.dkej123.devicecockpit.application.logcat.LogcatPackagePidTracker(
            scope, dispatchers, selectedPackageState,
            io.github.dkej123.devicecockpit.application.logcat.LogcatPidResolver(transport),
        )
        return io.github.dkej123.devicecockpit.application.logcat.LogcatControlsController(
            scope, dispatchers, selectedDeviceState, selectedPackageState, sessionManager, pidTracker,
            io.github.dkej123.devicecockpit.domain.logcat.FakeLogcatControlsPersistence(),
        )
    }

    private data class DisplayModels(
        val fontScale: FontScaleViewModel,
        val density: DensityViewModel,
        val quickToggles: QuickTogglesViewModel,
        val densityOverrideTracker: DensityOverrideTracker,
        val aggregator: DeviceContextAggregator,
    )

    private fun displayModels(scope: CoroutineScope, dispatchers: DispatcherProvider): DisplayModels {
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val commandContext = selectedDeviceState.value.toCommandContext()
        val densityOverrideTracker = DensityOverrideTracker()
        return DisplayModels(
            fontScale = FontScaleViewModel(
                scope = scope,
                dispatchers = dispatchers,
                transport = FakeAdbTransport(),
                commandContext = MutableStateFlow(commandContext),
            ),
            density = DensityViewModel(
                scope = scope,
                dispatchers = dispatchers,
                useCase = DensityUseCase(FakeAdbTransport(), densityOverrideTracker),
                commandContext = MutableStateFlow(commandContext),
            ),
            quickToggles = QuickTogglesViewModel(scope, dispatchers, FakeAdbTransport(), selectedDeviceState),
            densityOverrideTracker = densityOverrideTracker,
            aggregator = DeviceContextAggregator(scope, selectedDeviceState),
        )
    }

    private fun panel(
        dispatchers: DispatcherProvider,
        harness: Harness,
        openSettings: () -> Unit = {},
    ): AdbToolboxToolWindowPanel {
        val feedbackViewModel = FeedbackViewModel(harness.feedbackScope, dispatchers)
        val navigationVm = navigationViewModel(harness.navigationScope, dispatchers)
        val appsModels = appsModels(harness.appsScope, dispatchers, feedbackViewModel)
        val displayModels = displayModels(harness.displayScope, dispatchers)
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
            recordingViewModel = recordingViewModel(harness.recordingScope, dispatchers, feedbackViewModel),
            recordingScope = harness.recordingScope,
            appsViewModel = appsModels.apps,
            appLifecycleViewModel = appsModels.lifecycle,
            clearDataViewModel = appsModels.clearData,
            uninstallViewModel = appsModels.uninstall,
            appsScope = harness.appsScope,
            proxyController = proxyController(harness.networkScope, dispatchers),
            networkScope = harness.networkScope,
            logcatControlsController = logcatControlsController(harness.logcatScope, dispatchers),
            logcatScope = harness.logcatScope,
            fontScaleViewModel = displayModels.fontScale,
            densityViewModel = displayModels.density,
            quickTogglesViewModel = displayModels.quickToggles,
            densityOverrideTracker = displayModels.densityOverrideTracker,
            deviceContextAggregator = displayModels.aggregator,
            displayScope = harness.displayScope,
            openSettings = openSettings,
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

    fun `test the Apps view including destructive actions is registered in the feature host`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertTrue(panel.host.activeViewHost.isRegistered(ViewId.Apps.routeKey))

        panel.dispose()
    }

    fun `test the Network view is registered in the feature host`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertTrue(panel.host.activeViewHost.isRegistered(ViewId.Network.routeKey))

        panel.dispose()
    }

    fun `test the display sections live inside the Device view, with no Display view of their own`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        assertFalse(panel.host.activeViewHost.isRegistered("display"))
        val device = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey) as java.awt.Container
        fun descendants(c: java.awt.Component): List<java.awt.Component> =
            listOf(c) + ((c as? java.awt.Container)?.components?.flatMap(::descendants) ?: emptyList())
        assertTrue(descendants(device).any { it is io.github.dkej123.devicecockpit.intellij.display.DisplayPanel })

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
        assertFalse(harness.recordingScope.isActive)
        assertFalse(harness.appsScope.isActive)
        assertFalse(harness.networkScope.isActive)
        assertFalse(harness.displayScope.isActive)
        assertFalse(harness.logcatScope.isActive)
    }

    fun `test the screenshot control is mounted into the Device view alongside device facts`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        val deviceView = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey)
        assertTrue(deviceView is DeviceFactsPanel)
        val toolbar = (deviceView as DeviceFactsPanel).screenToolbar
        assertTrue(toolbar.components.any { it is io.github.dkej123.devicecockpit.intellij.ui.capture.CaptureView })

        panel.dispose()
    }

    fun `test the device-actions controls are mounted into the Device view alongside device facts and capture`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        val deviceView = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey)
        assertTrue(deviceView is DeviceFactsPanel)
        val deviceActionsSlot = (deviceView as DeviceFactsPanel).deviceActionsSlot
        assertTrue(deviceActionsSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test the mirroring control is mounted into the Device view alongside the other action-row controls`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        val deviceView = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey)
        assertTrue(deviceView is DeviceFactsPanel)
        val mirroringSlot = (deviceView as DeviceFactsPanel).mirroringSlot
        assertTrue(mirroringSlot.componentCount > 0)

        panel.dispose()
    }

    fun `test the recording control is mounted into the Device view alongside the other action-row controls`() {
        val dispatchers = dispatchers()
        val harness = Harness(dispatchers)
        val panel = panel(dispatchers, harness)

        val deviceView = panel.host.activeViewHost.componentFor(ViewId.Device.routeKey)
        assertTrue(deviceView is DeviceFactsPanel)
        val panelView = deviceView as DeviceFactsPanel
        // Mirror, Screenshot and Record share the Screen toolbar, in that order; Inspect has its own row.
        assertEquals(
            listOf("Start mirroring", "Screenshot", "Record"),
            panelView.screenToolbar.components.map { group ->
                ((group as java.awt.Container).getComponent(0) as javax.swing.JComponent).accessibleContext.accessibleName
            },
        )
        assertEquals(1, panelView.inspectSlot.componentCount)

        panel.dispose()
    }
}
