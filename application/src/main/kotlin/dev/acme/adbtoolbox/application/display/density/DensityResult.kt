package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.display.density.DensityValidationResult

/**
 * The exact-serial-scoped outcome of a [DensityUseCase] call. Every variant carries the [serial]
 * it answers for so a caller can discard a result for a serial that is no longer the selected
 * device (stale-serial suppression) rather than the use case guessing at an implicit "current
 * device." [Success.reading] always reflects a fresh readback (never the requested value) per the
 * "truth comes from readback, not the request" rule.
 */
sealed interface DensityResult {
    val serial: DeviceSerial

    data class Success(override val serial: DeviceSerial, val reading: DensityReading) : DensityResult

    /** A custom dpi request failed the safety-range check; no command was issued. */
    data class Invalid(override val serial: DeviceSerial, val validation: DensityValidationResult.OutOfRange) :
        DensityResult

    data class Unsupported(override val serial: DeviceSerial, val raw: String) : DensityResult

    data class PermissionDenied(override val serial: DeviceSerial, val raw: String) : DensityResult

    data class Malformed(override val serial: DeviceSerial, val raw: String, val reason: String) : DensityResult

    data class TransportError(override val serial: DeviceSerial, val outcome: AdbOutcome) : DensityResult
}
