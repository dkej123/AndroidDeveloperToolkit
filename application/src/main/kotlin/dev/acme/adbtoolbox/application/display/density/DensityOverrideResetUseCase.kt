package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetOutcome
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetUseCase

/**
 * The task 041 [OverrideResetUseCase] for density: delegates every mutation to the existing
 * [DensityUseCase] (task 027) — reusing its `wm density` command factory, safety validation, and
 * mandatory readback — never issuing an ADB command of its own. [tracker] is the same
 * [DensityOverrideTracker] [useCase] already records every readback into, so [currentValue]
 * answers from the identical device-truth source [DensityOverrideResetUseCase]'s own writes update.
 */
class DensityOverrideResetUseCase(
    private val useCase: DensityUseCase,
    private val tracker: DensityOverrideTracker,
) : OverrideResetUseCase {

    override val featureId: String = "density"

    override fun currentValue(serial: DeviceSerial): String? = tracker.overrideDpiFor(serial)?.toString()

    override suspend fun reset(serial: DeviceSerial): OverrideResetOutcome = useCase.reset(serial).toOutcome()

    override suspend fun reapply(serial: DeviceSerial, value: String): OverrideResetOutcome {
        val dpi = value.toIntOrNull() ?: return OverrideResetOutcome.Failed("Invalid stored density: $value")
        return useCase.applyCustom(serial, dpi).toOutcome()
    }
}

private fun DensityResult.toOutcome(): OverrideResetOutcome = when (this) {
    is DensityResult.Success -> OverrideResetOutcome.Success
    is DensityResult.Invalid -> OverrideResetOutcome.Failed(
        "Value must be between ${validation.minDpi} and ${validation.maxDpi} dpi",
    )
    is DensityResult.Unsupported -> OverrideResetOutcome.Failed("Not supported on this device: $raw")
    is DensityResult.PermissionDenied -> OverrideResetOutcome.Failed("Permission denied: $raw")
    is DensityResult.Malformed -> OverrideResetOutcome.Failed("Unexpected device output: $raw")
    is DensityResult.TransportError -> OverrideResetOutcome.Failed(outcome.describe())
}

private fun AdbOutcome.describe(): String = when (this) {
    is AdbOutcome.Completed -> "No output"
    AdbOutcome.TimedOut -> "Command timed out"
    AdbOutcome.Cancelled -> "Command was cancelled"
    is AdbOutcome.TransportFailure -> reason
    is AdbOutcome.Unsupported -> reason
}
