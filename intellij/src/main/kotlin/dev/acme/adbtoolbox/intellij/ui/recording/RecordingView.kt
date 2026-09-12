package dev.acme.adbtoolbox.intellij.ui.recording

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.recording.RecordingIntent
import dev.acme.adbtoolbox.application.recording.RecordingPresentationState
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.application.recording.RecordingViewState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import java.awt.FlowLayout
import javax.swing.JButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Task 020's minimal Device-view screen-recording binding: `design/README.md` §3's Capture section
 * **"Record"** / recording-banner **"Stop & save"** control, reduced to its minimal functional
 * shape (no final visual styling, banner chrome, or pulsing dot — later design tasks own those, same
 * as [dev.acme.adbtoolbox.intellij.ui.capture.CaptureView]'s own scope note). Follows
 * [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringView]'s established shape: the button never
 * decides start-vs-stop itself, every click only forwards [RecordingIntent.Toggle] to [viewModel];
 * [scope] is owned by the caller, state is collected and marshaled onto [dispatchers]' `main`
 * context before touching Swing, and [render] is `internal`/non-suspend for direct unit testing.
 *
 * The Reveal action itself is not a persistent button here, mirroring
 * [dev.acme.adbtoolbox.intellij.ui.capture.CaptureView]'s own reasoning: `design/README.md`'s
 * Capture section only ever offers Reveal as the success toast's action, which [RecordingViewModel]
 * already wires — a committed [dev.acme.adbtoolbox.domain.capture.CaptureLocation] existing is
 * exactly what gates that action, so a Reveal control can never point at a partial/failed/nonexistent
 * file.
 */
class RecordingView(
    private val viewModel: RecordingViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : JBPanel<RecordingView>(FlowLayout(FlowLayout.LEFT, 4, 0)), Disposable {

    val toggleButton = JButton("Record").apply {
        toolTipText = "Record the screen — max 3 minutes per adb"
        addActionListener { viewModel.handle(RecordingIntent.Toggle) }
    }

    val statusLabel = JBLabel("")

    init {
        add(toggleButton)
        add(statusLabel)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: RecordingViewState) {
        toggleButton.isEnabled = state.controlPolicy is ControlPolicy.Enabled &&
            state.presentationState !is RecordingPresentationState.Stopping &&
            state.presentationState !is RecordingPresentationState.Pulling
        toggleButton.text = when (state.presentationState) {
            RecordingPresentationState.Recording -> "Stop & save"
            RecordingPresentationState.Starting -> "Starting…"
            RecordingPresentationState.Stopping -> "Stopping…"
            RecordingPresentationState.Pulling -> "Saving…"
            RecordingPresentationState.Idle,
            RecordingPresentationState.Unavailable,
            is RecordingPresentationState.Error,
            -> "Record"
        }
        toggleButton.toolTipText = (state.presentationState as? RecordingPresentationState.Error)?.message
            ?: "Record the screen — max 3 minutes per adb"
        statusLabel.text = state.elapsedLabel?.let { "Recording · $it" } ?: ""
    }

    override fun dispose() {
        scope.cancel()
    }
}
