package dev.acme.adbtoolbox.application.recording

import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy

/**
 * The presentation-level projection of task 020's
 * [dev.acme.adbtoolbox.domain.recording.RecordingSessionState]. [Unavailable] has no session-state
 * equivalent — no eligible device is currently selected, so there is nothing to bind a session to.
 * [dev.acme.adbtoolbox.domain.recording.RecordingSessionState.Saved] always collapses back to [Idle]
 * here (mirroring [dev.acme.adbtoolbox.application.mirroring.MirroringPresentationState]'s own
 * `Exited -> Idle` collapse): the saved [dev.acme.adbtoolbox.domain.capture.CaptureLocation] is
 * surfaced through the feedback toast's Reveal action and [RecordingViewState.lastRecording]
 * instead of kept as its own lingering render state.
 */
sealed interface RecordingPresentationState {
    data object Unavailable : RecordingPresentationState
    data object Idle : RecordingPresentationState
    data object Starting : RecordingPresentationState
    data object Recording : RecordingPresentationState
    data object Stopping : RecordingPresentationState
    data object Pulling : RecordingPresentationState
    data class Error(val message: String) : RecordingPresentationState
}

/**
 * The immutable state task 020's Device-view recording control renders from. [controlPolicy]
 * mirrors the current device-eligibility signal (task 014), the same seam
 * [dev.acme.adbtoolbox.application.capture.CaptureViewState] and
 * [dev.acme.adbtoolbox.application.mirroring.MirroringViewState] already use. [elapsedLabel] is only
 * non-null while [presentationState] is [RecordingPresentationState.Recording] (`design/README.md`
 * §3's mono "Recording · 00:42"). [lastRecording] is set only once a recording has genuinely
 * committed to a local file — task 020's acceptance criteria: "Reveal appears only for a confirmed
 * local MP4" — so a Reveal control bound to it can never point at a partial/failed/nonexistent file.
 */
data class RecordingViewState(
    val controlPolicy: ControlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.Loading),
    val presentationState: RecordingPresentationState = RecordingPresentationState.Unavailable,
    val elapsedLabel: String? = null,
    val lastRecording: CaptureLocation? = null,
)
