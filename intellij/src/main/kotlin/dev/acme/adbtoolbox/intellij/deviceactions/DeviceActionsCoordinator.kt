package dev.acme.adbtoolbox.intellij.deviceactions

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.ui.deviceactions.DeviceActionsView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 016's [DeviceActionsView] into task 015's already-registered
 * [DeviceFactsPanel.actionsRow] — the same "fold this control into its own layout" seam
 * [dev.acme.adbtoolbox.intellij.capture.CaptureCoordinator] already uses for task 019's Screenshot
 * control, rather than registering a second [dev.acme.adbtoolbox.intellij.host.FeatureViewHost]
 * component for [dev.acme.adbtoolbox.domain.nav.ViewId.Device]'s route key (only one component may
 * ever be registered per route). [deviceFactsPanel] must therefore already be constructed, and
 * [scope]/[dispatchers] are this feature's own — cancelling [scope] via [dispose] never touches the
 * Device-facts or Capture features' own lifecycles.
 */
class DeviceActionsCoordinator(
    deviceFactsPanel: DeviceFactsPanel,
    viewModel: DeviceActionsViewModel,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
) : Disposable {

    val view: DeviceActionsView = DeviceActionsView(viewModel, scope, dispatchers).also { deviceFactsPanel.actionsRow.add(it) }

    override fun dispose() {
        view.dispose()
    }
}
