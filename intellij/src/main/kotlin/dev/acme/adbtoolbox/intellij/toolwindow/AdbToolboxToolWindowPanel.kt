package dev.acme.adbtoolbox.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import java.awt.BorderLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The neutral tool-window content itself (task 007's scope — no real feature UI, styling, icons,
 * or branding; those land in later feature/design tasks). Renders [ShellViewModel.state] and
 * nothing else: no ADB/process call and no branching business logic lives here, only rendering and
 * intent dispatch, per architecture-guardrails and ADR 0004.
 *
 * State updates arrive from [scope] (a feature-local, off-EDT-capable child scope) and are always
 * marshaled onto [dispatchers]' `main` context before touching a Swing component — collection
 * itself may run on any dispatcher, but every `statusLabel` mutation happens through `withContext`.
 */
class AdbToolboxToolWindowPanel(
    viewModel: ShellViewModel,
    dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
) : JBPanel<AdbToolboxToolWindowPanel>(BorderLayout()), Disposable {

    private val statusLabel = JBLabel(viewModel.state.value.statusMessage)

    init {
        add(statusLabel, BorderLayout.CENTER)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { statusLabel.text = state.statusMessage } }
            .launchIn(scope)

        scope.launch {
            viewModel.effects.collect { /* no toast/status-bar surface yet — task 007 has no real feature */ }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
