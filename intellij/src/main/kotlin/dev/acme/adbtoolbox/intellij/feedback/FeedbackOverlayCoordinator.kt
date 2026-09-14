package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.openapi.Disposable
import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.feedback.FeedbackViewState
import dev.acme.adbtoolbox.domain.devicecontext.DeviceContextSnapshot
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.BorderLayout
import java.awt.Rectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
    onResetOverrides: () -> Unit = {},
    deviceContext: Flow<DeviceContextSnapshot>? = null,
) : Disposable {

    val statusPanel = FeedbackStatusPanel(onResetOverrides = onResetOverrides)
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

        // Task 043: populates the status bar's "N overrides · reset all" chip (`design/README.md`
        // §8) from task 014's aggregated per-serial snapshot. Optional/nullable so every existing
        // caller/test that has no [DeviceContextSnapshot] source keeps working unchanged.
        deviceContext
            ?.onEach { snapshot -> withContext(dispatchers.main) { statusPanel.updateOverrideCount(snapshot.overrides.size) } }
            ?.launchIn(scope)
    }

    /** Production code always reaches this already marshaled onto [dispatchers]' `main` context. */
    internal fun render(state: FeedbackViewState) {
        statusPanel.update(state.status)
        toastStackPanel.update(state.toasts)
        overlays.show(toastStackPanel, ::toastBounds)
    }

    /**
     * `design/README.md`'s Global layout: "Toast is anchored bottom-left, above the status bar,
     * 8px inset". Height follows the stack's own preferred (toast-count-based) height.
     */
    private fun toastBounds(width: Int, height: Int): Rectangle {
        val inset = 8
        val insetWidth = (width - inset * 2).coerceAtLeast(0)
        val preferredHeight = toastStackPanel.preferredSize.height
        val bottom = height - AdbToolboxTheme.Sizes.statusBar - inset
        val top = (bottom - preferredHeight).coerceAtLeast(0)
        return Rectangle(inset, top, insetWidth, preferredHeight)
    }

    override fun dispose() {
        scope.cancel()
        overlays.dismiss(toastStackPanel)
    }
}
