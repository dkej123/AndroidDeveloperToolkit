package dev.acme.adbtoolbox.application.display.fontscale

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleCommands
import dev.acme.adbtoolbox.domain.display.fontscale.FontScalePresets
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleReadResult
import dev.acme.adbtoolbox.domain.display.fontscale.parseFontScale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads/writes/resets font scale for one device at a time (task 026), extracted from
 * [FontScaleViewModel] (task 041) so a reset can be driven both by that ViewModel's own `Reset`
 * intent and by [FontScaleOverrideResetUseCase] (the coordinator-facing port), without duplicating
 * the write-then-mandatory-readback command sequence in two places.
 *
 * [mutex] serializes every call through this instance exactly like
 * [dev.acme.adbtoolbox.application.display.density.DensityUseCase]'s: a rapid second write/reset
 * only starts once the previous one (including its readback) has finished, so two overlapping
 * callers — the ViewModel's own ops queue and a coordinator-triggered reset-all/re-apply — can
 * never interleave their `settings put system font_scale` commands.
 */
class FontScaleUseCase(
    private val transport: AdbTransport,
    private val overrides: FontScaleOverrides,
) {
    private val mutex = Mutex()

    suspend fun read(serial: DeviceSerial): FontScaleResult = mutex.withLock { readAndTrack(serial) }

    suspend fun write(serial: DeviceSerial, value: Double): FontScaleResult = mutex.withLock {
        val writeResult = transport.executeText(FontScaleCommands.write(serial, value))
        if (!writeResult.outcome.isSuccess()) {
            return@withLock FontScaleResult.Failed(serial, writeResult.outcome.describeFailure(writeResult.stderr))
        }
        readAndTrack(serial)
    }

    suspend fun reset(serial: DeviceSerial): FontScaleResult = write(serial, FontScalePresets.DEFAULT)

    private suspend fun readAndTrack(serial: DeviceSerial): FontScaleResult {
        val result = transport.executeText(FontScaleCommands.read(serial))
        if (!result.outcome.isSuccess()) {
            return FontScaleResult.Failed(serial, result.outcome.describeFailure(result.stderr))
        }
        return when (val parsed = parseFontScale(result.stdout)) {
            is FontScaleReadResult.Value -> {
                overrides.record(serial, parsed.value)
                FontScaleResult.Success(serial, parsed.value)
            }
            is FontScaleReadResult.Malformed -> FontScaleResult.Malformed(serial, parsed.raw)
        }
    }
}

internal fun AdbOutcome.isSuccess(): Boolean = this is AdbOutcome.Completed && (exitCode == null || exitCode == 0)

internal fun AdbOutcome.describeFailure(stderr: String): String = when (this) {
    is AdbOutcome.Completed -> stderr.ifBlank { "Command failed (exit code $exitCode)" }
    AdbOutcome.TimedOut -> "Command timed out"
    AdbOutcome.Cancelled -> "Command was cancelled"
    is AdbOutcome.TransportFailure -> reason
    is AdbOutcome.Unsupported -> reason
}
