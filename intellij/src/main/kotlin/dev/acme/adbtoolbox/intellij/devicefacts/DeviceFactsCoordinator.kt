package dev.acme.adbtoolbox.intellij.devicefacts

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsIntent
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewModel
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
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
 * [dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinator]/[dev.acme.adbtoolbox.intellij.feedback.FeedbackOverlayCoordinator]'s
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
) : Disposable {

    val panel: DeviceFactsPanel = host.registerFeatureView(ViewId.Device.routeKey) {
        DeviceFactsPanel(onCopyReport = { viewModel.handle(DeviceFactsIntent.CopyReport) })
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
