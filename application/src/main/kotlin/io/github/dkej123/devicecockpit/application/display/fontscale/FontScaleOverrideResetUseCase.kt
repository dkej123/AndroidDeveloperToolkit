package io.github.dkej123.devicecockpit.application.display.fontscale

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideResetOutcome
import io.github.dkej123.devicecockpit.domain.devicecontext.OverrideResetUseCase
import io.github.dkej123.devicecockpit.domain.display.fontscale.FontScalePresets

/**
 * The task 041 [OverrideResetUseCase] for font scale: delegates every mutation to [useCase]
 * (extracted from [FontScaleViewModel] in task 041) — reusing its command factory and mandatory
 * readback — never issuing an ADB command of its own. [overrides] is the same tracker [useCase]
 * already records every readback into, so [currentValue] answers from the identical device-truth
 * source this class's own writes update.
 */
class FontScaleOverrideResetUseCase(
    private val useCase: FontScaleUseCase,
    private val overrides: FontScaleOverrides,
) : OverrideResetUseCase {

    override val featureId: String = FontScaleOverrides.OVERRIDE_ID

    override fun currentValue(serial: DeviceSerial): String? =
        overrides.lastValueFor(serial)?.takeIf { it != FontScalePresets.DEFAULT }?.toString()

    override suspend fun reset(serial: DeviceSerial): OverrideResetOutcome = useCase.reset(serial).toOutcome()

    override suspend fun reapply(serial: DeviceSerial, value: String): OverrideResetOutcome {
        val target = value.toDoubleOrNull() ?: return OverrideResetOutcome.Failed("Invalid stored font scale: $value")
        return useCase.write(serial, target).toOutcome()
    }
}

private fun FontScaleResult.toOutcome(): OverrideResetOutcome = when (this) {
    is FontScaleResult.Success -> OverrideResetOutcome.Success
    is FontScaleResult.Malformed -> OverrideResetOutcome.Failed("Unexpected device output: $raw")
    is FontScaleResult.Failed -> OverrideResetOutcome.Failed(message)
}
