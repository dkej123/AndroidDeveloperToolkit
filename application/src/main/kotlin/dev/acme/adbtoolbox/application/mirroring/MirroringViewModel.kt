package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.nav.NavigationIntent
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.controlPolicy
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.RecoveryAction
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackAction
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.mirroring.MirroringExitReason
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringSessionError
import dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState
import dev.acme.adbtoolbox.domain.nav.ViewId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock

/**
 * The feature ViewModel for task 018's Device-view mirroring binding and global-shortcut action
 * (ADR 0004's MVI seam): both the `:intellij` button and the global [MirroringIntent.Toggle]
 * action forward to the exact same [handle] entry point, which resolves start-vs-stop from
 * task 017's [dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager] itself
 * (`sessionManager.stateFor(serial).value`, always current, never a stale cached copy) — so the
 * two triggers can never diverge and a rapid double-invocation (duplicate click, or click+shortcut)
 * can never start a second owned scrcpy process for the same serial: the second call observes the
 * first call's already-updated [MirroringSessionState] and issues a `stop()` instead of a second
 * `start()`.
 *
 * **Stale-session suppression**: [selectedDeviceState] drives which serial's session this
 * ViewModel observes. Switching the globally selected device cancels the previous serial's
 * subscription ([sessionJob]) before subscribing to the new one, so a still-running session for a
 * device that is no longer selected can never leak its state into this view (task 018's scope:
 * "suppress stale sessions after device changes") — matching
 * [dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager]'s own per-serial isolation
 * guarantee (task 017) one layer up.
 *
 * **Recovery routing**: a [MirroringSessionError.ToolUnavailable] carries task 004's own
 * [DiscoveryError.recovery] hint; when that hint is [RecoveryAction.SetPath] this posts a
 * [FeedbackMessage] whose single [FeedbackAction] navigates to [ViewId.Settings] via
 * [navigation] — no other "open settings" convention exists yet in this codebase (tasks 011/015/016
 * had no missing-tool recovery action to route), so this establishes it for later features to reuse.
 *
 * **External exit/errors**: every [MirroringSessionState.Error] and non-requested
 * [MirroringSessionState.Exited] is posted through [feedback] (task 013), while the rendered
 * [MirroringViewState.presentationState] itself always returns to [MirroringPresentationState.Idle]
 * on exit (task 018's acceptance criteria) — the reason is never lost, only relocated to the
 * non-modal channel instead of held as a lingering error render.
 */
class MirroringViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val sessionManager: MirroringSessionManager,
    private val feedback: FeedbackViewModel,
    private val navigation: NavigationViewModel,
    private val clock: Clock = Clock.System,
    /**
     * Task 040's seam for reading the currently-approved [MirroringOptions] at `start()` time —
     * always the *current* value, read fresh on every toggle, never cached at construction: a
     * [dev.acme.adbtoolbox.application.mirroring.MirroringOptionsViewModel] applying new options
     * between two toggles changes the very next `start()` call's arguments without this class ever
     * needing to know that a change happened. An already-running session is never affected, since
     * this is only ever consulted on the start branch below.
     */
    private val currentOptions: () -> MirroringOptions = { MirroringOptions.DEFAULT },
) {
    private val _state = MutableStateFlow(MirroringViewState())
    val state: StateFlow<MirroringViewState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var currentSerial: DeviceSerial? = null

    init {
        // Derive the initial state synchronously from selectedDeviceState's/the session manager's
        // *current* values (matching dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel's
        // seam) rather than waiting for the first async collector dispatch — a caller reading
        // [state] the instant this constructor returns (e.g. a real, non-test-dispatcher `:intellij`
        // view/action) must already see a correct value, not the placeholder default.
        onSelectedDeviceState(selectedDeviceState.value)
        selectedDeviceState
            .onEach(::onSelectedDeviceState)
            .launchIn(scope)
    }

    fun handle(intent: MirroringIntent) {
        when (intent) {
            MirroringIntent.Toggle -> toggle()
        }
    }

    private fun onSelectedDeviceState(deviceState: SelectedDeviceState) {
        val context = deviceState.toCommandContext()
        val policy = context.controlPolicy()
        when (context) {
            is DeviceCommandContext.Eligible -> {
                if (context.serial != currentSerial) {
                    subscribeTo(context.serial)
                }
                _state.update { it.copy(controlPolicy = policy) }
            }

            is DeviceCommandContext.Disabled -> {
                unsubscribe()
                _state.value = MirroringViewState(controlPolicy = policy, presentationState = MirroringPresentationState.Unavailable)
            }
        }
    }

    private fun subscribeTo(serial: DeviceSerial) {
        sessionJob?.cancel()
        currentSerial = serial
        val sessionState = sessionManager.stateFor(serial)
        // Synchronous initial read (mirrors the toggle()/init seam above): the manager's StateFlow
        // always has a current value, so this view's own [state] reflects it immediately rather
        // than waiting for the collector below's first async dispatch.
        _state.update { it.copy(presentationState = sessionState.value.toPresentation()) }
        sessionJob = sessionState
            .onEach(::onSessionState)
            .launchIn(scope)
    }

    private fun unsubscribe() {
        sessionJob?.cancel()
        sessionJob = null
        currentSerial = null
    }

    private fun onSessionState(sessionState: MirroringSessionState) {
        _state.update { it.copy(presentationState = sessionState.toPresentation()) }
        when (sessionState) {
            is MirroringSessionState.Error -> postErrorFeedback(sessionState.error)
            is MirroringSessionState.Exited -> postExitFeedback(sessionState.reason)
            else -> Unit
        }
    }

    private fun toggle() {
        val context = selectedDeviceState.value.toCommandContext()
        if (context !is DeviceCommandContext.Eligible) {
            feedback.handle(
                FeedbackIntent.Post(
                    FeedbackMessage(
                        id = "mirroring-no-device-${clock.now()}",
                        text = "No eligible device selected",
                        severity = FeedbackSeverity.Warning,
                    ),
                ),
            )
            return
        }

        val serial = context.serial
        when (sessionManager.stateFor(serial).value) {
            is MirroringSessionState.Starting, is MirroringSessionState.Running -> sessionManager.stop(serial)
            else -> sessionManager.start(serial, currentOptions())
        }

        // MirroringSessionManager.start()/stop() both mutate their StateFlow synchronously before
        // returning, but the async flow collector in subscribeTo() only processes that emission on
        // its next dispatch. Mirroring it here too makes a click/shortcut's effect on
        // [state] observable immediately, the same synchronous guarantee
        // dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel gives its own
        // [MirroringViewState.presentationState]-equivalent field.
        _state.update { it.copy(presentationState = sessionManager.stateFor(serial).value.toPresentation()) }
    }

    private fun postErrorFeedback(error: MirroringSessionError) {
        val text = describe(error)
        val action = (error as? MirroringSessionError.ToolUnavailable)?.let { recoveryActionFor(it.error) }
        feedback.handle(
            FeedbackIntent.Post(
                FeedbackMessage(
                    id = "mirroring-error-${clock.now()}",
                    text = text,
                    severity = FeedbackSeverity.Error,
                    action = action,
                ),
            ),
        )
    }

    private fun postExitFeedback(reason: MirroringExitReason) {
        val message = when (reason) {
            MirroringExitReason.Requested, MirroringExitReason.Cancelled -> null

            MirroringExitReason.ExternalWindowExit -> FeedbackMessage(
                id = "mirroring-exit-${clock.now()}",
                text = "Mirroring window closed",
                severity = FeedbackSeverity.Info,
            )

            is MirroringExitReason.ProcessExited -> FeedbackMessage(
                id = "mirroring-exit-${clock.now()}",
                text = "scrcpy exited unexpectedly (code ${reason.exitCode})" + (reason.detail?.let { ": $it" } ?: ""),
                severity = FeedbackSeverity.Error,
            )

            MirroringExitReason.Timeout -> FeedbackMessage(
                id = "mirroring-exit-${clock.now()}",
                text = "Mirroring timed out",
                severity = FeedbackSeverity.Error,
            )
        } ?: return

        feedback.handle(FeedbackIntent.Post(message))
    }

    private fun recoveryActionFor(discoveryError: DiscoveryError): FeedbackAction? =
        when (discoveryError.recovery) {
            is RecoveryAction.SetPath -> FeedbackAction(label = "Open Settings") { openSettings() }
            RecoveryAction.None -> null
        }

    private fun openSettings() {
        navigation.handle(NavigationIntent.Select(ViewId.Settings))
    }
}

private fun MirroringSessionState.toPresentation(): MirroringPresentationState = when (this) {
    is MirroringSessionState.Idle -> MirroringPresentationState.Idle
    is MirroringSessionState.Starting -> MirroringPresentationState.Starting
    is MirroringSessionState.Running -> MirroringPresentationState.Running
    is MirroringSessionState.Stopping -> MirroringPresentationState.Stopping
    is MirroringSessionState.Exited -> MirroringPresentationState.Idle
    is MirroringSessionState.Error -> MirroringPresentationState.Error(describe(error))
}

private fun describe(error: MirroringSessionError): String = when (error) {
    is MirroringSessionError.ToolUnavailable -> describe(error.error)
    is MirroringSessionError.StartFailure -> error.reason
}

private fun describe(discoveryError: DiscoveryError): String = when (discoveryError) {
    is DiscoveryError.ToolNotFound -> "scrcpy was not found. Configure its path in Settings."
    is DiscoveryError.ExecutableInvalid -> "The configured scrcpy path is invalid: ${discoveryError.reason}"
    is DiscoveryError.VersionQueryFailed -> "scrcpy version could not be determined: ${discoveryError.reason}"
}
