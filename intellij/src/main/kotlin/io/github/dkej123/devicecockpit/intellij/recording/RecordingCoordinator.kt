package io.github.dkej123.devicecockpit.intellij.recording

import com.intellij.openapi.Disposable
import io.github.dkej123.devicecockpit.application.recording.RecordingViewModel
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.intellij.devicefacts.DeviceFactsPanel
import io.github.dkej123.devicecockpit.intellij.ui.recording.RecordingView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 020's [RecordingView] into task 015's already-registered
 * [DeviceFactsPanel.captureSlot] — the same "fold this control into its own layout" seam
 * [io.github.dkej123.devicecockpit.intellij.capture.CaptureCoordinator],
 * [io.github.dkej123.devicecockpit.intellij.deviceactions.DeviceActionsCoordinator], and
 * [io.github.dkej123.devicecockpit.intellij.mirroring.MirroringCoordinator] already use, rather than registering
 * a second [io.github.dkej123.devicecockpit.intellij.host.FeatureViewHost] component for
 * [io.github.dkej123.devicecockpit.domain.nav.ViewId.Device]'s route key. [deviceFactsPanel] must therefore
 * already be constructed, and [scope]/[dispatchers] are this feature's own — cancelling [scope] via
 * [dispose] never touches the Device-facts, Capture, Device-actions, or Mirroring features' own
 * lifecycles.
 */
class RecordingCoordinator(
    deviceFactsPanel: DeviceFactsPanel,
    viewModel: RecordingViewModel,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
) : Disposable {

    val view: RecordingView = RecordingView(viewModel, scope, dispatchers).also { view ->
        deviceFactsPanel.screenToolbar.add(view)
        deviceFactsPanel.screenStatusSlot.add(view.statusRow)
    }

    override fun dispose() {
        view.dispose()
    }
}
