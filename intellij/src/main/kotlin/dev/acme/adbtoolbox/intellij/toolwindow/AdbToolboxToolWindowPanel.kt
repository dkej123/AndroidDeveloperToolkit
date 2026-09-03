package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import dev.acme.adbtoolbox.intellij.nav.NavigationRailPanel
import dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinator
import java.awt.BorderLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The tool-window content root. Mounts [host] — task 010's neutral [AdbToolboxHostPanel] with its
 * named slots for device context, navigation, active view, and feedback — replacing task 007's
 * bare placeholder. No real feature view is registered into [AdbToolboxHostPanel.activeViewHost]
 * yet (that seam is for tasks 011+); [ShellViewModel.state] is rendered into the host's
 * [AdbToolboxHostPanel.feedbackSlot] as a stand-in status line, matching `design/README.md`'s
 * status-bar region until a real feedback feature lands. No styling, icons, or branding here —
 * those land in later feature/design tasks.
 *
 * State updates arrive from [scope] (a feature-local, off-EDT-capable child scope) and are always
 * marshaled onto [dispatchers]' `main` context before touching a Swing component — collection
 * itself may run on any dispatcher, but every `statusLabel` mutation happens through `withContext`.
 *
 * [navigationViewModel] drives [AdbToolboxHostPanel.navigationSlot] (task 012): a
 * [NavigationRailPanel] is mounted there and connected to
 * [AdbToolboxHostPanel.activeViewHost] via [NavigationRoutingCoordinator], on its own
 * [navigationScope] child scope so navigation's lifecycle never depends on the shell status line's.
 */
class AdbToolboxToolWindowPanel(
    viewModel: ShellViewModel,
    dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    navigationViewModel: NavigationViewModel,
    private val navigationScope: CoroutineScope,
) : JBPanel<AdbToolboxToolWindowPanel>(BorderLayout()), Disposable {

    val host = AdbToolboxHostPanel()

    private val statusLabel = JBLabel(viewModel.state.value.statusMessage)

    private val navigationRail = NavigationRailPanel()

    private val navigationCoordinator = NavigationRoutingCoordinator(
        host = host,
        rail = navigationRail,
        viewModel = navigationViewModel,
        scope = navigationScope,
        dispatchers = dispatchers,
    )

    init {
        host.feedbackSlot.add(statusLabel, BorderLayout.CENTER)
        host.navigationSlot.add(navigationRail, BorderLayout.CENTER)
        add(host, BorderLayout.CENTER)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { statusLabel.text = state.statusMessage } }
            .launchIn(scope)

        scope.launch {
            viewModel.effects.collect { /* no toast/status-bar surface yet — task 007 has no real feature */ }
        }
    }

    override fun dispose() {
        navigationCoordinator.dispose()
        scope.cancel()
        host.dispose()
    }
}
