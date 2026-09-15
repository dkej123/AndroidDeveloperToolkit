package dev.acme.adbtoolbox.intellij.recording

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.ui.capture.CaptureView
import dev.acme.adbtoolbox.intellij.ui.recording.RecordingView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 020's [RecordingView] into task 015's already-registered
 * [DeviceFactsPanel.captureSlot] — the same "fold this control into its own layout" seam
 * [dev.acme.adbtoolbox.intellij.capture.CaptureCoordinator],
 * [dev.acme.adbtoolbox.intellij.deviceactions.DeviceActionsCoordinator], and
 * [dev.acme.adbtoolbox.intellij.mirroring.MirroringCoordinator] already use, rather than registering
 * a second [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] component for
 * [dev.acme.adbtoolbox.domain.nav.ViewId.Device]'s route key. [deviceFactsPanel] must therefore
 * already be constructed, and [scope]/[dispatchers] are this feature's own — cancelling [scope] via
 * [dispose] never touches the Device-facts, Capture, Device-actions, or Mirroring features' own
 * lifecycles.
 */
class RecordingCoordinator(
    deviceFactsPanel: DeviceFactsPanel,
    viewModel: RecordingViewModel,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    captureView: CaptureView? = null,
) : Disposable {

    val view: RecordingView = RecordingView(
        viewModel,
        scope,
        dispatchers,
        onRecordingVisibilityChanged = { recording -> captureView?.isVisible = !recording },
    ).also { deviceFactsPanel.captureSlot.add(it) }

    override fun dispose() {
        view.dispose()
    }
}
