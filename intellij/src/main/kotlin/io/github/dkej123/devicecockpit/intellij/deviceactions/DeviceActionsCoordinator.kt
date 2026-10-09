package io.github.dkej123.devicecockpit.intellij.deviceactions

import com.intellij.openapi.Disposable
import io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewModel
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsPanel
import io.github.dkej123.devicecockpit.intellij.ui.deviceactions.DeviceActionsView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 016's [DeviceActionsView] into task 015's already-registered
 * [DeviceFactsPanel.deviceActionsSlot] — the same "fold this control into its own layout" seam
 * [io.github.dkej123.devicecockpit.intellij.capture.CaptureCoordinator] already uses for task 019's Screenshot
 * control, rather than registering a second [io.github.dkej123.devicecockpit.intellij.host.FeatureViewHost]
 * component for [io.github.dkej123.devicecockpit.domain.nav.ViewId.Device]'s route key (only one component may
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

    val view: DeviceActionsView = DeviceActionsView(viewModel, scope, dispatchers).also { deviceFactsPanel.deviceActionsSlot.add(it) }

    override fun dispose() {
        view.dispose()
    }
}
