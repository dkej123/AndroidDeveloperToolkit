package dev.acme.adbtoolbox.application.devicefacts

import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactsSnapshot

/**
 * The functional presentation state of the Device view's facts section (task 015, ADR 0004's MVI
 * shape). [Partial] and [Connected] both carry a [DeviceFactsSnapshot] — the only difference is
 * whether every fact has settled ([DeviceFactsSnapshot.isSettled]); a settled fact can still be
 * [dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState.Unavailable] under [Connected], since
 * "connected" describes the device, not that every fact resolved successfully (task 015: "one
 * malformed fact must not blank the rest").
 */
sealed interface DeviceFactsViewState {

    /** No device is selected, or the selected device is not currently eligible (offline/unauthorized/stale). */
    data object NoDevice : DeviceFactsViewState

    /** Selection has not resolved yet, or an eligible device's facts have not started arriving yet. */
    data object Loading : DeviceFactsViewState

    /** An eligible device is selected and every fact has settled. */
    data class Connected(val snapshot: DeviceFactsSnapshot) : DeviceFactsViewState

    /** An eligible device is selected but at least one fact is still resolving. */
    data class Partial(val snapshot: DeviceFactsSnapshot) : DeviceFactsViewState

    /** A recoverable failure resolving the selected-device context itself (not a per-fact failure). */
    data class RecoverableError(val message: String) : DeviceFactsViewState
}
