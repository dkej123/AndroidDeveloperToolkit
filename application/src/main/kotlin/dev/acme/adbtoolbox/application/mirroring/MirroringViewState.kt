package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy

/**
 * The presentation-level projection of task 017's [dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState]
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
 * [dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewState] uses, so the control dims
 * with a reason rather than disappearing when no device is eligible.
 */
data class MirroringViewState(
    val controlPolicy: ControlPolicy = ControlPolicy.Disabled(DeviceCommandContext.Disabled.Loading),
    val presentationState: MirroringPresentationState = MirroringPresentationState.Unavailable,
)
