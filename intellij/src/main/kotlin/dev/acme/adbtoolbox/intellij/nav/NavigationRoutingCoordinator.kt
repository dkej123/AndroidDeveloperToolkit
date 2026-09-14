package dev.acme.adbtoolbox.intellij.nav

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.nav.NavigationIntent
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.domain.devicecontext.DeviceContextSnapshot
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.NavigationState
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects [viewModel]'s [NavigationState] to task 010's [AdbToolboxHostPanel] — the seam the
 * team-lead brief calls "the actual navigation UI/state that will call
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost.show]." Neither this class nor [viewModel]
 * ever grows a hardcoded per-feature branch: a later feature task registers its view into
 * [AdbToolboxHostPanel.activeViewHost] from its own file (task 010's seam), and the next
 * [NavigationState.Ready] emission here picks it up automatically via [dev.acme.adbtoolbox.domain.nav.ViewId.routeKey].
 *
 * **Unknown registration** (task 012 TDD plan: "selecting/restoring a ViewId that has no
 * registered feature view yet"): [AdbToolboxHostPanel.activeViewHost]'s own `show` throws for an
 * unregistered route key (task 010, by design — "fails loudly instead of silently no-oping" for a
 * *programming* error), so this coordinator checks `isRegistered` first and simply skips the
 * `show` call when the destination has no view yet — the rail still highlights it as selected
 * ([NavigationRailPanel.setSelected]), it just has no content to show, which is the real,
 * expected state of the world before every feature task has landed.
 *
 * **Focus handoff**: after a successful `show`, focus moves into the newly active view's root
 * component (`design/designs/ADB Toolbox IA.dc.html`'s keyboard model — a rail selection is
 * expected to hand off focus, not just repaint), via [javax.swing.JComponent.requestFocusInWindow]
 * — a no-op returning `false` when the host is not yet showing (e.g. during startup restoration
 * before the tool window is visible), so this never fights the IDE for initial focus.
 */
class NavigationRoutingCoordinator(
    private val host: AdbToolboxHostPanel,
    private val rail: NavigationRailPanel,
    private val viewModel: NavigationViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val openSettings: () -> Unit = {},
    deviceContext: Flow<DeviceContextSnapshot>? = null,
) : Disposable {

    init {
        rail.onSelect = { viewId -> viewModel.handle(NavigationIntent.Select(viewId)) }

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { route(state) } }
            .launchIn(scope)

        // Task 043: paints the rail's amber/red override/attention badges (`design/README.md`
        // §2) from task 014's aggregated per-serial snapshot. Optional/nullable so every existing
        // caller/test that has no [DeviceContextSnapshot] source keeps working unchanged.
        deviceContext
            ?.onEach { snapshot -> withContext(dispatchers.main) { rail.updateBadges(snapshot.badges) } }
            ?.launchIn(scope)
    }

    /**
     * The per-emission routing decision, `internal` and a plain (non-`suspend`) function so it is
     * directly unit-testable against constructed [NavigationState] values without depending on
     * [viewModel]'s own coroutine machinery — this module's established testing constraint (see
     * [dev.acme.adbtoolbox.intellij.host.HostPresentationSignalsTest]'s class doc for the same
     * "invoke the handler directly" pattern, and [dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectServiceTest]'s
     * `Dispatchers.EDT` class-doc note for why coroutine round trips are avoided in this headless
     * sandbox where avoidable). Production code always reaches this already marshaled onto
     * [dispatchers]' `main` context, from [init]'s collector.
     */
    internal fun route(state: NavigationState) {
        if (state !is NavigationState.Ready) return
        rail.setSelected(state.selected)
        if (state.selected == ViewId.Settings) {
            openSettings()
            return
        }
        val routeKey = state.selected.routeKey
        if (host.activeViewHost.isRegistered(routeKey)) {
            host.showFeatureView(routeKey)
            host.activeViewHost.componentFor(routeKey)?.requestFocusInWindow()
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
