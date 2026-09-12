package dev.acme.adbtoolbox.domain.devicefacts

/**
 * The result of resolving one [DeviceFactId], independent of every other fact's result (task 015:
 * "one malformed fact must not blank the rest"). [Unavailable] is the single uniform shape for
 * every way a fact can fail to resolve — malformed/unexpected device output, permission-denied
 * text, a transport failure, or a timeout — since a call site never needs to distinguish those
 * causes, only render [reason] as the fact's value.
 */
sealed interface DeviceFactState {
    data object Loading : DeviceFactState

    data class Available(val value: DeviceFactValue) : DeviceFactState

    data class Unavailable(val reason: String) : DeviceFactState
}
