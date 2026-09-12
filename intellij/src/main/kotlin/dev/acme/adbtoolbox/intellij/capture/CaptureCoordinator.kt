package dev.acme.adbtoolbox.intellij.capture

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.ui.capture.CaptureView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 019's [CaptureView] into task 015's already-registered
 * [DeviceFactsPanel.actionsRow], rather than registering a second
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] component for
 * [dev.acme.adbtoolbox.domain.nav.ViewId.Device]'s route key — that seam only ever holds one
 * component per route, and [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsCoordinator]
 * already claimed it. [deviceFactsPanel] must therefore already be constructed (this coordinator is
 * built after [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsCoordinator] in
 * `AdbToolboxToolWindowPanel`), and [scope]/[dispatchers] are this feature's own — cancelling
 * [scope] via [dispose] never touches the Device-facts feature's lifecycle.
 */
class CaptureCoordinator(
    deviceFactsPanel: DeviceFactsPanel,
    viewModel: CaptureViewModel,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
) : Disposable {

    val view: CaptureView = CaptureView(viewModel, scope, dispatchers).also { deviceFactsPanel.actionsRow.add(it) }

    override fun dispose() {
        view.dispose()
    }
}
