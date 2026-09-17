package dev.acme.adbtoolbox.application.display.fontscale

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * The exact-serial-scoped outcome of a [FontScaleUseCase] call, mirroring
 * [dev.acme.adbtoolbox.application.display.density.DensityResult]'s shape. [Success.value] always
 * reflects a fresh readback (never the requested value) per the "truth comes from readback, not
 * the request" rule (task 026).
 */
sealed interface FontScaleResult {
    val serial: DeviceSerial

    data class Success(override val serial: DeviceSerial, val value: Double) : FontScaleResult

    data class Malformed(override val serial: DeviceSerial, val raw: String) : FontScaleResult

    /** A transport failure, timeout, cancellation, or non-zero exit — [message] is already user-facing. */
    data class Failed(override val serial: DeviceSerial, val message: String) : FontScaleResult
}
