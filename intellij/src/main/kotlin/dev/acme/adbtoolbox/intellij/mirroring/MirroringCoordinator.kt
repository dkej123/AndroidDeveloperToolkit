package dev.acme.adbtoolbox.intellij.mirroring

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.mirroring.MirroringOptionsViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel
import dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringOptionsPanel
import dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringView
import kotlinx.coroutines.CoroutineScope

/**
 * Mounts task 018's [MirroringView] into the Device view's Screen section (design §3): the Mirror
 * split button into [DeviceFactsPanel.screenToolbar] (first), the inline options and scrcpy help
 * into [DeviceFactsPanel.mirroringSlot], and the "Mirroring · …" row into
 * [DeviceFactsPanel.screenStatusSlot]. [deviceFactsPanel] must therefore already be constructed,
 * and [scope]/[dispatchers] are this feature's own — cancelling [scope] via [dispose] never touches
 * the Device-facts, Capture, or Device-actions features' own lifecycles.
 */
class MirroringCoordinator(
    deviceFactsPanel: DeviceFactsPanel,
    viewModel: MirroringViewModel,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    /** Backs the inline "Mirroring options" panel; without it the caret has nothing to open. */
    optionsViewModel: MirroringOptionsViewModel? = null,
) : Disposable {

    val view: MirroringView = MirroringView(
        viewModel,
        scope,
        dispatchers,
        optionsPanel = optionsViewModel?.let { MirroringOptionsPanel(it, scope, dispatchers) },
    ).also { view ->
        deviceFactsPanel.screenToolbar.add(view.toolbarPart, 0)
        deviceFactsPanel.mirroringSlot.add(view)
        deviceFactsPanel.screenStatusSlot.add(view.statusRow, 0)
    }

    override fun dispose() {
        view.dispose()
    }
}
