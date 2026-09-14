package dev.acme.adbtoolbox.intellij.network

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.devicecontext.DeviceContextAggregator
import dev.acme.adbtoolbox.application.network.NetworkBadgeContributor
import dev.acme.adbtoolbox.application.network.ProxyController
import dev.acme.adbtoolbox.application.network.ProxyIntent
import dev.acme.adbtoolbox.application.network.ProxyOverrideSummaryContributor
import dev.acme.adbtoolbox.application.network.ProxyViewState
import dev.acme.adbtoolbox.domain.devicecontext.BadgeContributor
import dev.acme.adbtoolbox.domain.devicecontext.OverrideSummaryContributor
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 032's [ProxyController] to [NetworkPanel], following
 * [dev.acme.adbtoolbox.intellij.apps.AppsCoordinator]'s established shape: [scope] is owned by the
 * caller (never created here), state is collected and marshaled onto [dispatchers]' `main` context
 * before touching Swing, and [render] is `internal`/non-suspend so it is directly unit-testable
 * against constructed state values without depending on a real coroutine round trip through this
 * headless test sandbox. Every panel callback forwards to [controller] as a [ProxyIntent] —
 * [dispose] cancels [scope] and disposes the panel's own listeners, cancelling any in-flight
 * host/device work the controller itself was still tracking (task 032's "cancel stale host/device
 * work and dispose subscriptions").
 */
class NetworkCoordinator(
    private val controller: ProxyController,
    private val aggregator: DeviceContextAggregator,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    badgeContributor: BadgeContributor = NetworkBadgeContributor(controller),
    overrideContributor: OverrideSummaryContributor = ProxyOverrideSummaryContributor(controller),
) : Disposable {

    val panel = NetworkPanel(
        onHostChange = { text -> controller.handle(ProxyIntent.EditHost(text)) },
        onPortChange = { text -> controller.handle(ProxyIntent.EditPort(text)) },
        onUseComputerIp = { controller.handle(ProxyIntent.UseComputerIp) },
        onEnable = {
            val state = controller.state.value
            controller.handle(ProxyIntent.Enable(state.hostInput, state.portInput))
        },
        onReset = { controller.handle(ProxyIntent.Reset) },
        onSelectRecent = { endpoint -> controller.handle(ProxyIntent.SelectRecent(endpoint)) },
    )

    private val badgeRegistration = aggregator.registerBadgeContributor(badgeContributor)
    private val overrideRegistration = aggregator.registerOverrideSummaryContributor(overrideContributor)

    init {
        controller.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: ProxyViewState) {
        panel.update(state)
        aggregator.refresh()
    }

    override fun dispose() {
        overrideRegistration.unregister()
        badgeRegistration.unregister()
        scope.cancel()
        panel.disposePanel()
    }
}
