package io.github.dkej123.devicecockpit.application.mirroring

import io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext
import io.github.dkej123.devicecockpit.domain.devicecontext.ControlPolicy

/**
 * The presentation-level projection of task 017's [io.github.dkej123.devicecockpit.domain.mirroring.MirroringSessionState]
 * (task 018's scope: "map unavailable/idle/starting/running/stopping/error session states to
 * presentation models"). [Unavailable] has no equivalent session state at all — it means no
 * eligible device is currently selected, so there is nothing to bind a session to yet;
 * [MirroringSessionState.Exited] always collapses to [Idle] here (task 018's acceptance criteria:
 * "UI returns to idle on external exit"), with the exit's reason surfaced separately through the
 * feedback channel instead of kept as its own presentation state.
 */
sealed interface MirroringPresentationState {
    data object Unavailable : MirroringPresentationState
    data object Idle : MirroringPresentationState
    data object Starting : MirroringPresentationState
    data object Running : MirroringPresentationState
    data object Stopping : MirroringPresentationState
    data class Error(val message: String) : MirroringPresentationState
}

/**
 * The immutable state task 018's Device-view mirroring control and global action both render from.
 * [controlPolicy] mirrors the current device-eligibility signal (task 014), the same seam
 * [io.github.dkej123.devicecockpit.application.deviceactions.DeviceActionsViewState] uses, so the control dims
 * with a reason rather than disappearing when no device is eligible.
 */
data class MirroringViewState(
    val controlPolicy: ControlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.Loading),
    val presentationState: MirroringPresentationState = MirroringPresentationState.Unavailable,
    /** Whether scrcpy is usable; [ScrcpyAvailability.Missing] greys out starting and explains the fix. */
    val scrcpy: ScrcpyAvailability = ScrcpyAvailability.Checking,
)
