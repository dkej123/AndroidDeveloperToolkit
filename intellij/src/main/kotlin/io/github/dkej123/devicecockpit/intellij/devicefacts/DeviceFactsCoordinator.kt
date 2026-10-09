package io.github.dkej123.devicecockpit.intellij.devicefacts

import com.intellij.openapi.Disposable
import io.github.dkej123.devicecockpit.application.devicefacts.DeviceFactsIntent
import io.github.dkej123.devicecockpit.application.devicefacts.DeviceFactsViewModel
import io.github.dkej123.devicecockpit.application.devicefacts.DeviceFactsViewState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.nav.ViewId
import io.github.dkej123.devicecockpit.intellij.host.AdbToolboxHostPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 015's [DeviceFactsViewModel] to task 010's [AdbToolboxHostPanel]: registers
 * [DeviceFactsPanel] into [AdbToolboxHostPanel.activeViewHost] under [ViewId.Device]'s route key —
 * the first feature view this project actually registers (every earlier task's `FeatureViewHost`
 * seam has stayed unused). Follows
 * [io.github.dkej123.devicecockpit.intellij.nav.NavigationRoutingCoordinator]/[io.github.dkej123.devicecockpit.intellij.feedback.FeedbackOverlayCoordinator]'s
 * established shape: [scope] is owned by the caller, state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and [render] is `internal`/non-suspend so
 * it is directly unit-testable without a real coroutine round trip through this headless test
 * sandbox.
 */
class DeviceFactsCoordinator(
    host: AdbToolboxHostPanel,
    private val viewModel: DeviceFactsViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    onRefresh: () -> Unit,
    onPairOverWifi: () -> Unit,
) : Disposable {

    constructor(
        host: AdbToolboxHostPanel,
        viewModel: DeviceFactsViewModel,
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
    ) : this(host, viewModel, scope, dispatchers, {}, {})

    val panel: DeviceFactsPanel = host.registerFeatureView(ViewId.Device.routeKey) {
        DeviceFactsPanel(
            onCopyReport = { viewModel.handle(DeviceFactsIntent.CopyReport) },
            onRefresh = onRefresh,
            onPairOverWifi = onPairOverWifi,
        )
    } as DeviceFactsPanel

    init {
        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: DeviceFactsViewState) {
        panel.update(state)
    }

    override fun dispose() {
        scope.cancel()
    }
}
