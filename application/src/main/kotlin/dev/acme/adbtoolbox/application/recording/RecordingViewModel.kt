package dev.acme.adbtoolbox.application.recording

import dev.acme.adbtoolbox.application.feedback.FeedbackIntent
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.controlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackAction
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.recording.RecordingSessionError
import dev.acme.adbtoolbox.domain.recording.RecordingSessionState
import dev.acme.adbtoolbox.domain.recording.formatElapsed
import dev.acme.adbtoolbox.domain.time.MonotonicClock
import dev.acme.adbtoolbox.domain.time.SystemMonotonicClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * The feature ViewModel for task 020's minimal Device-view recording binding (ADR 0004's MVI seam),
 * mirroring task 018's [dev.acme.adbtoolbox.application.mirroring.MirroringViewModel] shape: the
 * `:intellij` control forwards every click to the same [handle] entry point, which resolves
 * start-vs-stop from [dev.acme.adbtoolbox.application.recording.RecordingSessionManager]'s own
 * current state — never a locally cached copy — so a rapid double-click can never start a second
 * owned `screenrecord` session for the same serial.
 *
 * **Stale-session suppression**: [selectedDeviceState] drives which serial's session this
 * ViewModel observes, exactly like [dev.acme.adbtoolbox.application.mirroring.MirroringViewModel]:
 * switching the globally selected device cancels the previous serial's subscription before
 * subscribing to the new one, so a still-recording session for a device that is no longer selected
 * can never leak its state into this view.
 *
 * **Elapsed ticking**: while [RecordingSessionState.Recording] is current, a child ticker recomputes
 * [RecordingViewState.elapsedLabel] every [tickInterval] from [monotonicClock] — the exact same
 * clock instance [RecordingSessionManager] stamped [RecordingSessionState.Recording.startedAt] with,
 * so both readings are always comparable. The ticker is cancelled the instant the session leaves
 * [RecordingSessionState.Recording] (stop, auto-completion, error, or device switch), so no orphan
 * timer ever outlives its session — [tickInterval] uses [kotlinx.coroutines.delay], which
 * `kotlinx-coroutines-test`'s virtual time advances deterministically, never a real wall-clock wait.
 *
 * **Terminal outcomes**: [RecordingSessionState.Saved] and [RecordingSessionState.Error] are both
 * posted through [feedback] (task 013) — a save carries a Reveal [FeedbackAction] and is recorded in
 * [RecordingViewState.lastRecording] (task 020's "Reveal appears only for a confirmed local MP4");
 * an error surfaces its reason as-is. Both then present as [RecordingPresentationState.Idle] /
 * [RecordingPresentationState.Error] respectively, so a stale [RecordingPresentationState.Pulling]
 * render can never survive past the outcome that ended it.
 */
class RecordingViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val sessionManager: RecordingSessionManager,
    private val revealInFileManager: RevealInFileManager,
    private val feedback: FeedbackViewModel,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val clock: Clock = Clock.System,
    private val tickInterval: Duration = 1.seconds,
) {
    private val _state = MutableStateFlow(RecordingViewState())
    val state: StateFlow<RecordingViewState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var tickerJob: Job? = null
    private var currentSerial: DeviceSerial? = null

    init {
        onSelectedDeviceState(selectedDeviceState.value)
        selectedDeviceState
            .onEach(::onSelectedDeviceState)
            .launchIn(scope)
    }

    fun handle(intent: RecordingIntent) {
        when (intent) {
            RecordingIntent.Toggle -> toggle()
            RecordingIntent.RevealLastRecording -> revealLast()
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
                _state.value = RecordingViewState(controlPolicy = policy, presentationState = RecordingPresentationState.Unavailable)
            }
        }
    }

    private fun subscribeTo(serial: DeviceSerial) {
        sessionJob?.cancel()
        currentSerial = serial
        val sessionState = sessionManager.stateFor(serial)
        applySessionState(sessionState.value)
        sessionJob = sessionState
            .onEach(::applySessionState)
            .launchIn(scope)
    }

    private fun unsubscribe() {
        sessionJob?.cancel()
        sessionJob = null
        currentSerial = null
        stopTicker()
    }

    private fun applySessionState(sessionState: RecordingSessionState) {
        _state.update {
            it.copy(
                presentationState = sessionState.toPresentation(),
                elapsedLabel = elapsedLabelFor(sessionState),
            )
        }
        when (sessionState) {
            is RecordingSessionState.Recording -> startTicker(sessionState.startedAt)
            else -> stopTicker()
        }
        when (sessionState) {
            is RecordingSessionState.Saved -> {
                _state.update { it.copy(lastRecording = sessionState.location) }
                feedback.handle(
                    FeedbackIntent.Post(
                        FeedbackMessage(
                            id = "recording-success-${sessionState.location.displayPath}",
                            text = "Saved ${sessionState.location.displayPath}",
                            severity = FeedbackSeverity.Success,
                            action = FeedbackAction("Reveal") { revealInFileManager.reveal(sessionState.location) },
                        ),
                    ),
                )
            }

            is RecordingSessionState.Error -> {
                feedback.handle(
                    FeedbackIntent.Post(
                        FeedbackMessage(
                            id = "recording-error-${clock.now()}",
                            text = describe(sessionState.error),
                            severity = FeedbackSeverity.Error,
                        ),
                    ),
                )
            }

            else -> Unit
        }
    }

    private fun elapsedLabelFor(sessionState: RecordingSessionState): String? =
        if (sessionState is RecordingSessionState.Recording) {
            formatElapsed(monotonicClock.markNow() - sessionState.startedAt)
        } else {
            null
        }

    private fun startTicker(startedAt: Duration) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                _state.update { it.copy(elapsedLabel = formatElapsed(monotonicClock.markNow() - startedAt)) }
                delay(tickInterval)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun toggle() {
        val context = selectedDeviceState.value.toCommandContext()
        if (context !is DeviceCommandContext.Eligible) {
            feedback.handle(
                FeedbackIntent.Post(
                    FeedbackMessage(
                        id = "recording-no-device-${clock.now()}",
                        text = "No eligible device selected",
                        severity = FeedbackSeverity.Warning,
                    ),
                ),
            )
            return
        }

        val serial = context.serial
        when (sessionManager.stateFor(serial).value) {
            is RecordingSessionState.Starting, is RecordingSessionState.Recording -> sessionManager.stop(serial)
            else -> sessionManager.start(serial)
        }

        // RecordingSessionManager.start()/stop() both mutate their StateFlow synchronously before
        // returning, but the async flow collector in subscribeTo() only processes that emission on
        // its next dispatch — mirrored here so a click's effect on [state] is observable immediately
        // (same seam as dev.acme.adbtoolbox.application.mirroring.MirroringViewModel.toggle()).
        applySessionState(sessionManager.stateFor(serial).value)
    }

    private fun revealLast() {
        state.value.lastRecording?.let { revealInFileManager.reveal(it) }
    }
}

private fun RecordingSessionState.toPresentation(): RecordingPresentationState = when (this) {
    is RecordingSessionState.Idle -> RecordingPresentationState.Idle
    is RecordingSessionState.Starting -> RecordingPresentationState.Starting
    is RecordingSessionState.Recording -> RecordingPresentationState.Recording
    is RecordingSessionState.Stopping -> RecordingPresentationState.Stopping
    is RecordingSessionState.Pulling -> RecordingPresentationState.Pulling
    is RecordingSessionState.Saved -> RecordingPresentationState.Idle
    is RecordingSessionState.Error -> RecordingPresentationState.Error(describe(error))
}

private fun describe(error: RecordingSessionError): String = when (error) {
    is RecordingSessionError.StartFailure -> error.reason
    is RecordingSessionError.PullFailure -> error.reason
}
