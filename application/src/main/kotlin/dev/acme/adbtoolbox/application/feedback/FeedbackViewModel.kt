package dev.acme.adbtoolbox.application.feedback

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** `design/tokens/tokens.json`'s `toastMaxStack` — the bounded toast queue's stack limit. */
const val DEFAULT_TOAST_MAX_STACK: Int = 3

/** `design/tokens/tokens.json`'s `toastAutoDismissMs` — success/info/warning toast auto-expiry. */
val DEFAULT_TOAST_AUTO_DISMISS: Duration = 4000.milliseconds

/**
 * The one non-modal feedback channel (task 013, ADR 0004's MVI shape): a bounded, ordered toast
 * queue plus the separate persistent [dev.acme.adbtoolbox.domain.feedback.StatusState] toast text
 * mirrors into. [scope]/[dispatchers] follow the same feature-local-child-scope/dispatcher-
 * injection seam as every other ViewModel in this module (e.g.
 * [dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel]); [scope] is owned by the
 * caller, never created here.
 *
 * **Queue bound** ([maxStack], default [DEFAULT_TOAST_MAX_STACK]): `design/README.md` specifies
 * "max 3 stacked" but not a drop policy for the overflow case. This class drops the oldest toast
 * to admit a new one (FIFO eviction) — the newest, most relevant-to-what-the-user-just-did
 * message always stays visible, matching the append-only ordering [state] otherwise preserves.
 *
 * **Expiry**: every toast except [FeedbackSeverity.Error] is scheduled to auto-dismiss after
 * [autoDismissAfter] via a plain `delay()` on [scope] — deterministic under
 * `kotlinx-coroutines-test`'s virtual time in tests (no real wall-clock sleep), the same pattern
 * `:adapters-adb`'s `AdbDeviceRepository` poll ticker uses. [FeedbackSeverity.Error] toasts never
 * auto-expire — `design/README.md`: "error = ... persists" — so they remain actionable until
 * [FeedbackIntent.Dismiss] or [FeedbackIntent.InvokeAction] removes them.
 *
 * **Disposal**: [dispose] is distinct from cancelling [scope] — cancelling the scope alone would
 * still let an in-flight [handle] call mutate [state] synchronously (a `MutableStateFlow` update
 * does not check job-active state), which would violate "a message posted to an already-disposed
 * feedback channel must be safely ignored". [dispose] instead flips an explicit flag every mutation
 * path checks first, so every [handle] call and every pending expiry timer's callback becomes a
 * safe no-op once disposed, and [state] is left exactly as it was at the moment of disposal.
 */
class FeedbackViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val maxStack: Int = DEFAULT_TOAST_MAX_STACK,
    private val autoDismissAfter: Duration = DEFAULT_TOAST_AUTO_DISMISS,
) {
    private val _state = MutableStateFlow(FeedbackViewState())
    val state: StateFlow<FeedbackViewState> = _state.asStateFlow()

    @Volatile
    private var disposed = false

    fun handle(intent: FeedbackIntent) {
        if (disposed) return
        when (intent) {
            is FeedbackIntent.Post -> post(intent.message)
            is FeedbackIntent.Dismiss -> dismiss(intent.id)
            is FeedbackIntent.InvokeAction -> invokeAction(intent.id)
            is FeedbackIntent.SetProcess -> setProcess(intent.indicator)
        }
    }

    private fun post(message: FeedbackMessage) {
        _state.update { current ->
            val bounded = if (current.toasts.size >= maxStack) current.toasts.drop(1) else current.toasts
            current.copy(
                toasts = bounded + message,
                status = current.status.copy(message = message.text),
            )
        }
        if (message.severity != FeedbackSeverity.Error) {
            scheduleExpiry(message.id)
        }
    }

    private fun scheduleExpiry(id: String) {
        scope.launch(dispatchers.default) {
            delay(autoDismissAfter)
            dismiss(id)
        }
    }

    private fun dismiss(id: String) {
        if (disposed) return
        _state.update { it.copy(toasts = it.toasts.filterNot { toast -> toast.id == id }) }
    }

    private fun invokeAction(id: String) {
        val toast = _state.value.toasts.find { it.id == id } ?: return
        val action = toast.action ?: return
        action.invoke()
        dismiss(id)
    }

    private fun setProcess(indicator: ProcessIndicator) {
        _state.update { it.copy(status = it.status.copy(process = indicator)) }
    }

    /**
     * Rejects every subsequent [handle] call and any already-scheduled expiry timer as a safe
     * no-op — no disposed feedback channel receives a timer callback (task 013's disposal
     * requirement). Idempotent.
     */
    fun dispose() {
        disposed = true
    }
}
