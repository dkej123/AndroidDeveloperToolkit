package dev.acme.adbtoolbox.application.layout

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.display.density.DensityCommands
import dev.acme.adbtoolbox.domain.display.density.DensityParseResult
import dev.acme.adbtoolbox.domain.display.density.parseDensityText
import dev.acme.adbtoolbox.domain.layout.UiAutomatorDumpCommand
import dev.acme.adbtoolbox.domain.layout.UiDumpRead
import dev.acme.adbtoolbox.domain.layout.UiHierarchy
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/** A screenshot and the hierarchy of the same screen (ADR 0014). */
class LayoutSnapshot(val png: ByteArray, val hierarchy: UiHierarchy)

/** A screenshot with the density to read it in dp — what is left when the UI tree cannot be read. */
class ScreenImage(val png: ByteArray, val densityDpi: Int)

/** Outcome of a layout capture. */
sealed interface LayoutCapture<out T> {
    data class Captured<T>(val value: T) : LayoutCapture<T>

    data class Failed(val reason: String) : LayoutCapture<Nothing>
}

/**
 * Captures the screen's UI hierarchy (`uiautomator dump`) with the effective density, and optionally
 * a screenshot taken alongside, for the Layout Inspector, the accessibility audit and the MCP tools
 * (task 057). uiautomator refuses while the screen animates, so an empty dump is retried with backoff
 * (task 066).
 *
 * Ported from Oh My Android, MIT — `Sources/Core/Android/LayoutSnapshot.swift` (`UIAutomatorSnapshotReader`).
 */
class CaptureLayoutUseCase(private val adbTransport: AdbTransport) {

    suspend fun hierarchy(serial: DeviceSerial): LayoutCapture<UiHierarchy> {
        val density = density(serial) ?: return LayoutCapture.Failed("Could not read the display density.")
        ATTEMPT_DELAYS.forEach { wait ->
            delay(wait)
            when (val read = UiAutomatorDumpCommand.parse(adbTransport.executeText(UiAutomatorDumpCommand.request(serial)))) {
                is UiDumpRead.Dumped -> return LayoutCapture.Captured(UiHierarchy(read.root, density))
                is UiDumpRead.Malformed -> return LayoutCapture.Failed("Could not read the view hierarchy: ${read.reason}.")
                is UiDumpRead.TransportFailed -> return LayoutCapture.Failed("Could not reach the device.")
                UiDumpRead.Empty -> Unit
            }
        }
        return LayoutCapture.Failed(
            "uiautomator returned nothing: the screen kept animating. Wait and retry, or turn animations off " +
                "(MCP: set_device_settings animations=false).",
        )
    }

    /** The screenshot and density only, for when the UI tree cannot be read (task 066). */
    suspend fun screen(serial: DeviceSerial): LayoutCapture<ScreenImage> {
        val density = density(serial) ?: return LayoutCapture.Failed("Could not read the display density.")
        val png = screenshot(serial) ?: return LayoutCapture.Failed("Could not take the screenshot.")
        return LayoutCapture.Captured(ScreenImage(png, density))
    }

    suspend fun snapshot(serial: DeviceSerial): LayoutCapture<LayoutSnapshot> = coroutineScope {
        val screenshot = async { screenshot(serial) }
        when (val hierarchy = hierarchy(serial)) {
            is LayoutCapture.Failed -> hierarchy.also { screenshot.cancel() }
            is LayoutCapture.Captured -> screenshot.await()
                ?.let { LayoutCapture.Captured(LayoutSnapshot(it, hierarchy.value)) }
                ?: LayoutCapture.Failed("Could not take the screenshot.")
        }
    }

    private suspend fun screenshot(serial: DeviceSerial): ByteArray? {
        val chunks = mutableListOf<ByteArray>()
        val request = AdbDeviceRequest(serial, AdbOperation.Exec(listOf("screencap", "-p")), timeout = 15.seconds)
        val outcome = adbTransport.executeBinary(request) { chunks += it }
        val bytes = chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }
        val succeeded = outcome is AdbOutcome.Completed && (outcome.exitCode ?: 0) == 0
        return bytes.takeIf { succeeded && it.isNotEmpty() }
    }

    private suspend fun density(serial: DeviceSerial): Int? =
        when (val parsed = parseDensityText(adbTransport.executeText(AdbDeviceRequest(serial, DensityCommands.read())).stdout)) {
            is DensityParseResult.Parsed -> parsed.reading.overrideDpi ?: parsed.reading.physicalDpi
            else -> null
        }

    private companion object {
        /** Wait before each attempt: none, then 500 ms, then 1 s. */
        val ATTEMPT_DELAYS = listOf(0.milliseconds, 500.milliseconds, 1000.milliseconds)
    }
}
