package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarCoordinator
import dev.acme.adbtoolbox.intellij.feedback.FeedbackOverlayCoordinator
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import dev.acme.adbtoolbox.intellij.nav.NavigationRailPanel
import dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinator
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
 * [deviceBarViewModel] drives [AdbToolboxHostPanel.deviceContextSlot] and its picker overlay
 * (task 011) via [DeviceContextBarCoordinator], on its own [deviceBarScope] child scope so the
 * device bar's lifecycle never depends on navigation/feedback's.
 */
class AdbToolboxToolWindowPanel(
    viewModel: ShellViewModel,
    dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    navigationViewModel: NavigationViewModel,
    private val navigationScope: CoroutineScope,
    feedbackViewModel: FeedbackViewModel,
    private val feedbackScope: CoroutineScope,
    deviceBarViewModel: DeviceBarViewModel,
    private val deviceBarScope: CoroutineScope,
) : JBPanel<AdbToolboxToolWindowPanel>(BorderLayout()), Disposable {

    val host = AdbToolboxHostPanel()

    private val navigationRail = NavigationRailPanel()

    private val deviceContextBarCoordinator = DeviceContextBarCoordinator(
        host = host,
        viewModel = deviceBarViewModel,
        scope = deviceBarScope,
        dispatchers = dispatchers,
    )

    private val navigationCoordinator = NavigationRoutingCoordinator(
        host = host,
        rail = navigationRail,
        viewModel = navigationViewModel,
        scope = navigationScope,
        dispatchers = dispatchers,
    )

    private val feedbackCoordinator = FeedbackOverlayCoordinator(
        host = host,
        viewModel = feedbackViewModel,
        scope = feedbackScope,
        dispatchers = dispatchers,
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
        deviceContextBarCoordinator.dispose()
        scope.cancel()
        host.dispose()
    }
}
