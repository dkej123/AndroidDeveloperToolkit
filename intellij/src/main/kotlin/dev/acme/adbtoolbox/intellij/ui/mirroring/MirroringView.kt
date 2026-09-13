package dev.acme.adbtoolbox.intellij.ui.mirroring

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.mirroring.MirroringIntent
import dev.acme.adbtoolbox.application.mirroring.MirroringPresentationState
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringViewState
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
 * Task 018's minimal Device-view mirroring binding: one toggle button — no options editing,
 * capture, or final visual styling (task 018's out-of-scope list; later tasks own those). Follows
 * [dev.acme.adbtoolbox.intellij.ui.deviceactions.DeviceActionsView]'s established shape: [scope] is
 * owned by the caller, state is collected and marshaled onto [dispatchers]' `main` context before
 * touching Swing, and [render] is `internal`/non-suspend for direct unit testing.
 *
 * The button never decides start-vs-stop itself — every click only forwards
 * [MirroringIntent.Toggle] to [viewModel], the exact same intent
 * [dev.acme.adbtoolbox.intellij.mirroring.MirroringToggleAction] (task 018's global shortcut)
 * forwards, so the two triggers can never diverge.
 */
class MirroringView(
    private val viewModel: MirroringViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    /** Task 040's "22px options icon button" (`design/README.md` §3): opens
     * [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringOptionsDialog] directly from the Device
     * view, independent of any session state — never itself touches [viewModel] or a running
     * session. Injected rather than constructed here so this view stays free of a [com.intellij.openapi.project.Project]
     * dependency and easily testable. */
    private val openOptions: () -> Unit = {},
) : JBPanel<MirroringView>(FlowLayout(FlowLayout.LEFT, 4, 0)), Disposable {

    val toggleButton = JButton("Start mirroring").apply {
        addActionListener { viewModel.handle(MirroringIntent.Toggle) }
    }

    val optionsButton = JButton("Options…").apply {
        toolTipText = "Mirroring options — bitrate, resolution, stay awake"
        addActionListener { openOptions() }
    }

    init {
        add(toggleButton)
        add(optionsButton)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: MirroringViewState) {
        toggleButton.isEnabled = state.controlPolicy is ControlPolicy.Enabled
        optionsButton.isEnabled = state.controlPolicy is ControlPolicy.Enabled
        toggleButton.text = when (state.presentationState) {
            MirroringPresentationState.Running -> "Stop mirroring"
            MirroringPresentationState.Starting -> "Starting…"
            MirroringPresentationState.Stopping -> "Stopping…"
            MirroringPresentationState.Idle,
            MirroringPresentationState.Unavailable,
            is MirroringPresentationState.Error,
            -> "Start mirroring"
        }
        toggleButton.toolTipText = (state.presentationState as? MirroringPresentationState.Error)?.message
    }

    override fun dispose() {
        scope.cancel()
    }
}
