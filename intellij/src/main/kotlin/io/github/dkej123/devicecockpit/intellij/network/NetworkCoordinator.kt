package io.github.dkej123.devicecockpit.intellij.network

import io.github.dkej123.devicecockpit.application.network.NetworkThrottleViewModel
import io.github.dkej123.devicecockpit.application.network.NetworkThrottleIntent
import com.intellij.openapi.Disposable
import io.github.dkej123.devicecockpit.application.devicecontext.DeviceContextAggregator
import io.github.dkej123.devicecockpit.application.network.NetworkBadgeContributor
import io.github.dkej123.devicecockpit.application.network.ProxyController
import io.github.dkej123.devicecockpit.application.network.ProxyIntent
import io.github.dkej123.devicecockpit.application.network.ProxyOverrideSummaryContributor
import io.github.dkej123.devicecockpit.application.network.ProxyViewState
import io.github.dkej123.devicecockpit.domain.devicecontext.BadgeContributor
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideSummaryContributor
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 032's [ProxyController] to [NetworkPanel], following
 * [io.github.dkej123.devicecockpit.intellij.apps.AppsCoordinator]'s established shape: [scope] is owned by the
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
    private val throttleViewModel: NetworkThrottleViewModel? = null,
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
        onApplyThrottle = { throttle -> throttleViewModel?.handle(NetworkThrottleIntent.Apply(throttle)) },
    )

    private val badgeRegistration = aggregator.registerBadgeContributor(badgeContributor)
    private val overrideRegistration = aggregator.registerOverrideSummaryContributor(overrideContributor)

    init {
        controller.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
        throttleViewModel?.state
            ?.onEach { state -> withContext(dispatchers.main) { panel.update(state) } }
            ?.launchIn(scope)
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
