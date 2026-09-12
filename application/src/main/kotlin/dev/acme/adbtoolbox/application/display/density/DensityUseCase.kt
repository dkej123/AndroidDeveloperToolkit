package dev.acme.adbtoolbox.application.display.density

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.display.density.DensityCommands
import dev.acme.adbtoolbox.domain.display.density.DensityParseResult
import dev.acme.adbtoolbox.domain.display.density.DensityValidationResult
import dev.acme.adbtoolbox.domain.display.density.parseDensityText
import dev.acme.adbtoolbox.domain.display.density.percentToDpi
import dev.acme.adbtoolbox.domain.display.density.validateCustomDensity
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val REQUEST_TIMEOUT = 5.seconds

/**
 * Applies/reads/resets display density for one device at a time (task 027). Every mutation is
 * followed by a fresh `wm density` readback whose value — not the requested one — becomes both the
 * returned [DensityResult.Success.reading] and what [overrideTracker] records, since a device is
 * free to clamp or otherwise not apply a requested dpi exactly.
 *
 * [mutex] serializes every call through this instance: a rapid second apply/reset only starts
 * once the previous one (including its readback) has finished, so two overlapping calls can never
 * interleave their `wm density` commands or race to update [overrideTracker] with a stale reading.
 */
class DensityUseCase(
    private val transport: AdbTransport,
    private val overrideTracker: DensityOverrideTracker,
) {
    private val mutex = Mutex()

    suspend fun read(serial: DeviceSerial): DensityResult = mutex.withLock { readAndTrack(serial) }

    suspend fun applyPreset(serial: DeviceSerial, percent: Int): DensityResult = mutex.withLock {
        val current = readAndTrack(serial)
        val physicalDpi = (current as? DensityResult.Success)?.reading?.physicalDpi
            ?: return@withLock current
        applyDpi(serial, percentToDpi(physicalDpi, percent))
    }

    suspend fun applyCustom(serial: DeviceSerial, requestedDpi: Int): DensityResult = mutex.withLock {
        val current = readAndTrack(serial)
        val physicalDpi = (current as? DensityResult.Success)?.reading?.physicalDpi
            ?: return@withLock current
        when (val validation = validateCustomDensity(physicalDpi, requestedDpi)) {
            is DensityValidationResult.Valid -> applyDpi(serial, validation.dpi)
            is DensityValidationResult.OutOfRange -> DensityResult.Invalid(serial, validation)
        }
    }

    suspend fun reset(serial: DeviceSerial): DensityResult = mutex.withLock {
        val result = transport.executeText(AdbDeviceRequest(serial, DensityCommands.reset(), REQUEST_TIMEOUT))
        if (result.outcome !is AdbOutcome.Completed) return@withLock DensityResult.TransportError(serial, result.outcome)
        readAndTrack(serial)
    }

    private suspend fun applyDpi(serial: DeviceSerial, dpi: Int): DensityResult {
        val result = transport.executeText(AdbDeviceRequest(serial, DensityCommands.apply(dpi), REQUEST_TIMEOUT))
        if (result.outcome !is AdbOutcome.Completed) return DensityResult.TransportError(serial, result.outcome)
        return readAndTrack(serial)
    }

    private suspend fun readAndTrack(serial: DeviceSerial): DensityResult {
        val result = transport.executeText(AdbDeviceRequest(serial, DensityCommands.read(), REQUEST_TIMEOUT))
        val densityResult = result.toDensityResult(serial)
        if (densityResult is DensityResult.Success) {
            overrideTracker.record(serial, densityResult.reading)
        }
        return densityResult
    }
}

private fun AdbTextResult.toDensityResult(serial: DeviceSerial): DensityResult {
    val outcome = outcome
    if (outcome !is AdbOutcome.Completed) return DensityResult.TransportError(serial, outcome)

    val fromStdout = parseDensityText(stdout)
    val effective = if (fromStdout is DensityParseResult.Malformed && stderr.isNotBlank()) {
        val fromStderr = parseDensityText(stderr)
        if (fromStderr is DensityParseResult.Malformed) fromStdout else fromStderr
    } else {
        fromStdout
    }

    return when (effective) {
        is DensityParseResult.Parsed -> DensityResult.Success(serial, effective.reading)
        is DensityParseResult.Unsupported -> DensityResult.Unsupported(serial, effective.raw)
        is DensityParseResult.PermissionDenied -> DensityResult.PermissionDenied(serial, effective.raw)
        is DensityParseResult.Malformed -> DensityResult.Malformed(serial, effective.raw, effective.reason)
    }
}
