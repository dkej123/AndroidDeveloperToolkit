package dev.acme.adbtoolbox.intellij.ui.capture

import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.capture.CaptureIntent
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.capture.CaptureViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import java.awt.FlowLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Task 019's minimal functional Device-view binding: exposing exactly one control —
 * `design/IMPLEMENTATION.md` §4's **"Screenshot"** button — with no other Capture/Device-view
 * chrome. Task 015 landed the Device view first, so this is not registered as its own
 * [dev.acme.adbtoolbox.intellij.host.FeatureViewHost] route; instead
 * `dev.acme.adbtoolbox.intellij.capture.CaptureCoordinator` mounts this panel into task 015's
 * already-registered [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel.captureSlot] — the
 * "fold this control into its own layout" outcome this class's earlier revision anticipated. Task
 * 044 owns final visual styling.
 *
 * The Reveal action itself is not a persistent button here — `design/README.md`'s Capture section
 * only ever offers Reveal as the success toast's action (`design/IMPLEMENTATION.md` §7's "screenshot
 * ... with ... a Reveal action on the result toast"), which [CaptureViewModel] already wires; a
 * committed [dev.acme.adbtoolbox.domain.capture.CaptureLocation] existing is exactly what gates that
 * action, so a Reveal control can never point at a partial/failed/nonexistent file.
 *
 * Follows [dev.acme.adbtoolbox.intellij.feedback.FeedbackOverlayCoordinator]'s established shape:
 * [scope] is owned by the caller, state is collected and marshaled onto [dispatchers]' `main`
 * context before touching Swing, and [render] is `internal`/non-suspend for direct unit testing.
 */
class CaptureView(
    private val viewModel: CaptureViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : JBPanel<CaptureView>(FlowLayout(FlowLayout.LEFT, 0, 0)), Disposable {

    /** Where screenshots go, as shown to the user (see DeviceSectionMetaViewModel). */
    private var destinationLabel = "~/Desktop"
    private var lastState = CaptureViewState()

    val screenshotButton = DesignButton("Screenshot", DesignButtonStyle.SECONDARY).apply {
        toolTipText = "Save a PNG to $destinationLabel"
        addActionListener { viewModel.handle(CaptureIntent.CaptureScreenshot) }
    }

    init {
        isOpaque = false
        add(screenshotButton)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: CaptureViewState) {
        lastState = state
        screenshotButton.isEnabled = state.controlPolicy is ControlPolicy.Enabled && !state.isCapturing
        screenshotButton.toolTipText = if (state.controlPolicy is ControlPolicy.Enabled) {
            "Save a PNG to $destinationLabel"
        } else {
            "Save a PNG to $destinationLabel — Connect a device to use this"
        }
    }

    /** Must be called on the EDT. */
    fun setDestinationLabel(label: String) {
        destinationLabel = label
        render(lastState)
    }

    override fun dispose() {
        scope.cancel()
    }
}
