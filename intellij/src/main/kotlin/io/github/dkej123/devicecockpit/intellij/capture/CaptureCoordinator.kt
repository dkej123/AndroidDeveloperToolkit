package io.github.dkej123.devicecockpit.intellij.capture

import com.intellij.openapi.Disposable
import io.github.dkej123.devicecockpit.application.capture.CaptureViewModel
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsPanel
import io.github.dkej123.devicecockpit.intellij.ui.capture.CaptureView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 019's [CaptureView] into task 015's already-registered
 * [DeviceFactsPanel.captureSlot], rather than registering a second
 * [io.github.dkej123.devicecockpit.intellij.host.FeatureViewHost] component for
 * [io.github.dkej123.devicecockpit.domain.nav.ViewId.Device]'s route key — that seam only ever holds one
 * component per route, and [io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsCoordinator]
 * already claimed it. [deviceFactsPanel] must therefore already be constructed (this coordinator is
 * built after [io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsCoordinator] in
 * `AdbToolboxToolWindowPanel`), and [scope]/[dispatchers] are this feature's own — cancelling
 * [scope] via [dispose] never touches the Device-facts feature's lifecycle.
 */
class CaptureCoordinator(
    deviceFactsPanel: DeviceFactsPanel,
    viewModel: CaptureViewModel,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
) : Disposable {

    val view: CaptureView = CaptureView(viewModel, scope, dispatchers).also { deviceFactsPanel.screenToolbar.add(it) }

    override fun dispose() {
        view.dispose()
    }
}
