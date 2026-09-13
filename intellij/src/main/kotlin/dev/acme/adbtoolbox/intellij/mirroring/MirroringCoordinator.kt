package dev.acme.adbtoolbox.intellij.mirroring

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 018's [MirroringView] into task 015's already-registered
 * [DeviceFactsPanel.actionsRow] — the same "fold this control into its own layout" seam
 * [dev.acme.adbtoolbox.intellij.capture.CaptureCoordinator] and
 * [dev.acme.adbtoolbox.intellij.deviceactions.DeviceActionsCoordinator] already use, rather than
 * registering a second [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] component for
 * [dev.acme.adbtoolbox.domain.nav.ViewId.Device]'s route key. [deviceFactsPanel] must therefore
 * already be constructed, and [scope]/[dispatchers] are this feature's own — cancelling [scope] via
 * [dispose] never touches the Device-facts, Capture, or Device-actions features' own lifecycles.
 */
class MirroringCoordinator(
    deviceFactsPanel: DeviceFactsPanel,
    viewModel: MirroringViewModel,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    /** Task 040's options-icon-button action, forwarded verbatim to [MirroringView] — opens
     * [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringOptionsDialog], never wired here directly
     * so this coordinator (like [MirroringView]) stays free of a [com.intellij.openapi.project.Project]
     * dependency. */
    openOptions: () -> Unit = {},
) : Disposable {

    val view: MirroringView = MirroringView(viewModel, scope, dispatchers, openOptions)
        .also { deviceFactsPanel.actionsRow.add(it) }

    override fun dispose() {
        view.dispose()
    }
}
