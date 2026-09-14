package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewModel
import dev.acme.adbtoolbox.application.apps.AppsViewModel
import dev.acme.adbtoolbox.application.apps.ClearDataViewModel
import dev.acme.adbtoolbox.application.apps.UninstallViewModel
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewModel
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewModel
import dev.acme.adbtoolbox.application.display.QuickTogglesViewModel
import dev.acme.adbtoolbox.application.display.density.DensityViewModel
import dev.acme.adbtoolbox.application.display.fontscale.FontScaleViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.logcat.LogcatControlsController
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.network.ProxyController
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.capture.CaptureCoordinator
import dev.acme.adbtoolbox.intellij.apps.AppsCoordinator
import dev.acme.adbtoolbox.intellij.deviceactions.DeviceActionsCoordinator
import dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarCoordinator
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsCoordinator
import dev.acme.adbtoolbox.intellij.display.DisplayCoordinator
import dev.acme.adbtoolbox.intellij.feedback.FeedbackOverlayCoordinator
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import dev.acme.adbtoolbox.intellij.logcat.LogcatCoordinator
import dev.acme.adbtoolbox.intellij.mirroring.MirroringCoordinator
import dev.acme.adbtoolbox.intellij.nav.NavigationRailPanel
import dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinator
import dev.acme.adbtoolbox.intellij.network.NetworkCoordinator
import dev.acme.adbtoolbox.intellij.recording.RecordingCoordinator
import java.awt.BorderLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The tool-window content root. Mounts [host] — task 010's neutral [AdbToolboxHostPanel] with its
 * named slots for device context, navigation, active view, and feedback — replacing task 007's
 * bare placeholder.
 *
 * [feedbackViewModel] drives [AdbToolboxHostPanel.feedbackSlot] and its toast overlay (task 013)
 * via [FeedbackOverlayCoordinator], on its own [feedbackScope] child scope — replacing task 007's
 * [ShellViewModel]-status-text stand-in that used to be rendered directly into `feedbackSlot`.
 * [ShellViewModel] itself is kept (it remains the seam proving the MVI pattern end to end for
 * `:intellij`'s composition); its effects are simply no longer surfaced through `feedbackSlot`.
 *
 * [navigationViewModel] drives [AdbToolboxHostPanel.navigationSlot] (task 012): a
 * [NavigationRailPanel] is mounted there and connected to
 * [AdbToolboxHostPanel.activeViewHost] via [NavigationRoutingCoordinator], on its own
 * [navigationScope] child scope so navigation's lifecycle never depends on the feedback channel's.
 *
 * [deviceFactsViewModel] drives the Device view's facts section (task 015) via
 * [DeviceFactsCoordinator], on its own [deviceFactsScope] child scope — the first real feature view
 * registered into [AdbToolboxHostPanel.activeViewHost] (`ViewId.Device`'s route key), constructed
 * before [navigationCoordinator] so the Device view is already registered for
 * [NavigationRoutingCoordinator]'s initial routing decision.
 *
 * [deviceBarViewModel] drives [AdbToolboxHostPanel.deviceContextSlot] and its picker overlay
 * (task 011) via [DeviceContextBarCoordinator], on its own [deviceBarScope] child scope so the
 * device bar's lifecycle never depends on navigation/feedback/device-facts'.
 *
 * [captureViewModel] drives task 019's Device-view "Screenshot" control via [CaptureCoordinator],
 * on its own [captureScope] child scope. It is constructed after [deviceFactsCoordinator] because it
 * mounts into [deviceFactsCoordinator]'s already-registered panel rather than registering its own
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] route (see [CaptureCoordinator]'s class doc).
 *
 * [deviceActionsViewModel] drives task 016's Device-view Reboot/Open-shell/Wake controls via
 * [DeviceActionsCoordinator], on its own [deviceActionsScope] child scope. It is constructed after
 * [captureCoordinator] for the same "mounts into an already-registered panel" reason.
 *
 * [mirroringViewModel] drives task 018's Device-view mirroring toggle via [MirroringCoordinator],
 * on its own [mirroringScope] child scope. It is constructed after [deviceActionsCoordinator] for
 * the same "mounts into an already-registered panel" reason.
 *
 * [recordingViewModel] drives task 020's Device-view recording toggle via [RecordingCoordinator],
 * on its own [recordingScope] child scope. It is constructed after [mirroringCoordinator] for the
 * same "mounts into an already-registered panel" reason.
 *
 * [proxyController] drives task 032's Network view via [NetworkCoordinator], on its own
 * [networkScope] child scope, registering its own [ViewId.Network] route into
 * [AdbToolboxHostPanel.activeViewHost] the same way [appsCoordinator] registers [ViewId.Apps].
 *
 * [fontScaleViewModel]/[densityViewModel]/[quickTogglesViewModel] drive task 029's Display view via
 * [DisplayCoordinator], on its own [displayScope] child scope, registering its own [ViewId.Display]
 * route the same way [networkCoordinator] registers [ViewId.Network]; [deviceContextAggregator]
 * (task 014) is where it publishes its badge/override contributions.
 *
 * [logcatControlsController] drives task 037's Logcat view via [LogcatCoordinator], on its own
 * [logcatScope] child scope, registering its own [ViewId.Logcat] route the same way
 * [networkCoordinator] registers [ViewId.Network]; it publishes its own error-badge contribution
 * into [deviceContextAggregator] the same way [displayCoordinator] does.
 */
class AdbToolboxToolWindowPanel(
    viewModel: ShellViewModel,
    dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    navigationViewModel: NavigationViewModel,
    private val navigationScope: CoroutineScope,
    feedbackViewModel: FeedbackViewModel,
    private val feedbackScope: CoroutineScope,
    deviceFactsViewModel: DeviceFactsViewModel,
    private val deviceFactsScope: CoroutineScope,
    deviceBarViewModel: DeviceBarViewModel,
    private val deviceBarScope: CoroutineScope,
    captureViewModel: CaptureViewModel,
    private val captureScope: CoroutineScope,
    deviceActionsViewModel: DeviceActionsViewModel,
    private val deviceActionsScope: CoroutineScope,
    mirroringViewModel: MirroringViewModel,
    private val mirroringScope: CoroutineScope,
    recordingViewModel: RecordingViewModel,
    private val recordingScope: CoroutineScope,
    appsViewModel: AppsViewModel,
    appLifecycleViewModel: AppLifecycleViewModel,
    clearDataViewModel: ClearDataViewModel,
    uninstallViewModel: UninstallViewModel,
    private val appsScope: CoroutineScope,
    proxyController: ProxyController,
    private val networkScope: CoroutineScope,
    logcatControlsController: LogcatControlsController,
    private val logcatScope: CoroutineScope,
    fontScaleViewModel: FontScaleViewModel,
    densityViewModel: DensityViewModel,
    quickTogglesViewModel: QuickTogglesViewModel,
    densityOverrideTracker: OverrideSummaryContributor,
    deviceContextAggregator: DeviceContextAggregator,
    private val displayScope: CoroutineScope,
    openSettings: () -> Unit = {},
    openMirroringOptions: () -> Unit = {},
) : JBPanel<AdbToolboxToolWindowPanel>(BorderLayout()), Disposable {

    val host = AdbToolboxHostPanel()

    private val navigationRail = NavigationRailPanel()

    // Registers the Device view (task 015) into host.activeViewHost before NavigationRoutingCoordinator
    // is constructed, so an initial NavigationState.Ready(ViewId.Device) emission finds it registered.
    private val deviceFactsCoordinator = DeviceFactsCoordinator(
        host = host,
        viewModel = deviceFactsViewModel,
        scope = deviceFactsScope,
        dispatchers = dispatchers,
    )

    private val deviceContextBarCoordinator = DeviceContextBarCoordinator(
        host = host,
        viewModel = deviceBarViewModel,
        scope = deviceBarScope,
        dispatchers = dispatchers,
    )

    // Mounted after deviceFactsCoordinator so its actionsRow already exists to append into.
    private val captureCoordinator = CaptureCoordinator(
        deviceFactsPanel = deviceFactsCoordinator.panel,
        viewModel = captureViewModel,
        scope = captureScope,
        dispatchers = dispatchers,
    )

    // Mounted after captureCoordinator, into the same already-registered actionsRow.
    private val deviceActionsCoordinator = DeviceActionsCoordinator(
        deviceFactsPanel = deviceFactsCoordinator.panel,
        viewModel = deviceActionsViewModel,
        scope = deviceActionsScope,
        dispatchers = dispatchers,
    )

    // Mounted after deviceActionsCoordinator, into the same already-registered actionsRow.
    private val mirroringCoordinator = MirroringCoordinator(
        deviceFactsPanel = deviceFactsCoordinator.panel,
        viewModel = mirroringViewModel,
        scope = mirroringScope,
        dispatchers = dispatchers,
        openOptions = openMirroringOptions,
    )

    // Mounted after mirroringCoordinator, into the same already-registered actionsRow.
    private val recordingCoordinator = RecordingCoordinator(
        deviceFactsPanel = deviceFactsCoordinator.panel,
        viewModel = recordingViewModel,
        scope = recordingScope,
        dispatchers = dispatchers,
    )

    private val appsCoordinator = AppsCoordinator(
        viewModel = appsViewModel,
        appLifecycleViewModel = appLifecycleViewModel,
        clearDataViewModel = clearDataViewModel,
        uninstallViewModel = uninstallViewModel,
        scope = appsScope,
        dispatchers = dispatchers,
    )

    init {
        host.activeViewHost.registerFeatureView(ViewId.Apps.routeKey) { appsCoordinator.panel }
    }

    private val networkCoordinator = NetworkCoordinator(
        controller = proxyController,
        aggregator = deviceContextAggregator,
        scope = networkScope,
        dispatchers = dispatchers,
    )

    init {
        host.activeViewHost.registerFeatureView(ViewId.Network.routeKey) { networkCoordinator.panel }
    }

    private val logcatCoordinator = LogcatCoordinator(
        controller = logcatControlsController,
        aggregator = deviceContextAggregator,
        scope = logcatScope,
        dispatchers = dispatchers,
    )

    init {
        host.activeViewHost.registerFeatureView(ViewId.Logcat.routeKey) { logcatCoordinator.panel }
    }

    private val displayCoordinator = DisplayCoordinator(
        host = host,
        fontScaleViewModel = fontScaleViewModel,
        densityViewModel = densityViewModel,
        quickTogglesViewModel = quickTogglesViewModel,
        densityOverrideTracker = densityOverrideTracker,
        feedback = feedbackViewModel,
        aggregator = deviceContextAggregator,
        scope = displayScope,
        dispatchers = dispatchers,
    )

    private val navigationCoordinator = NavigationRoutingCoordinator(
        host = host,
        rail = navigationRail,
        viewModel = navigationViewModel,
        scope = navigationScope,
        dispatchers = dispatchers,
        openSettings = openSettings,
        deviceContext = deviceContextAggregator.state,
    )

    private val feedbackCoordinator = FeedbackOverlayCoordinator(
        host = host,
        viewModel = feedbackViewModel,
        scope = feedbackScope,
        dispatchers = dispatchers,
        deviceContext = deviceContextAggregator.state,
    )

    init {
        host.navigationSlot.add(navigationRail, BorderLayout.CENTER)
        add(host, BorderLayout.CENTER)

        scope.launch {
            viewModel.effects.collect { /* no feature-specific wiring yet — task 007 has no real feature */ }
        }
    }

    override fun dispose() {
        navigationCoordinator.dispose()
        feedbackCoordinator.dispose()
        displayCoordinator.dispose()
        logcatCoordinator.dispose()
        networkCoordinator.dispose()
        appsCoordinator.dispose()
        recordingCoordinator.dispose()
        mirroringCoordinator.dispose()
        deviceActionsCoordinator.dispose()
        captureCoordinator.dispose()
        deviceFactsCoordinator.dispose()
        deviceContextBarCoordinator.dispose()
        scope.cancel()
        host.dispose()
    }
}
