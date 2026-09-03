package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import java.awt.BorderLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Connects task 013's [FeedbackViewModel] to task 010's [AdbToolboxHostPanel]: [FeedbackStatusPanel]
 * is mounted into [AdbToolboxHostPanel.feedbackSlot] (replacing task 007's `ShellViewModel`
 * status-text stand-in — `AdbToolboxToolWindowPanel`'s class doc), and [ToastStackPanel] is shown
 * as one bounded overlay in [AdbToolboxHostPanel.overlays] (`design/IMPLEMENTATION.md` §3: toasts
 * live in the tool window's `JLayeredPane`).
 *
 * Follows [dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinator]'s established shape:
 * [scope] is owned by the caller (never created here), state is collected and marshaled onto
 * [dispatchers]' `main` context before touching Swing, and [render] is `internal`/non-suspend so
 * it is directly unit-testable against constructed [FeedbackViewState] values without depending on
 * a real coroutine round trip through this headless test sandbox (the same "invoke the handler
 * directly" pattern documented on [dev.acme.adbtoolbox.intellij.nav.NavigationRoutingCoordinator.route]).
 */
class FeedbackOverlayCoordinator(
    host: AdbToolboxHostPanel,
    private val viewModel: FeedbackViewModel,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : Disposable {

    val statusPanel = FeedbackStatusPanel()
    private val toastStackPanel = ToastStackPanel(
        onAction = { id -> viewModel.handle(FeedbackIntent.InvokeAction(id)) },
        onDismiss = { id -> viewModel.handle(FeedbackIntent.Dismiss(id)) },
    )

    private val overlays = host.overlays

    init {
        host.feedbackSlot.add(statusPanel, BorderLayout.CENTER)
        overlays.show(toastStackPanel)

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: FeedbackViewState) {
        statusPanel.update(state.status)
        toastStackPanel.update(state.toasts)
    }

    override fun dispose() {
        scope.cancel()
        overlays.dismiss(toastStackPanel)
    }
}
