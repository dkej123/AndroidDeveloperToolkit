package dev.acme.adbtoolbox.domain.network

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * Emulator network-throttling presets, each a pair of emulator-console profiles. [downloadBitsPerSecond]
 * and [maxLatencyMs] are what `emu network status` reports for them (measured on the emulator), used to
 * recognize the preset on readback.
 */
enum class NetworkThrottle(
    val label: String,
    val speedProfile: String,
    val delayProfile: String,
    val downloadBitsPerSecond: Long,
    val maxLatencyMs: Int,
) {
    Off("Off", "full", "none", 0, 0),
    Lte("LTE", "lte", "none", 173_000_000, 0),
    Hspa("HSPA", "hsdpa", "umts", 13_980_000, 200),
    Umts("3G", "umts", "umts", 384_000, 200),
    Edge("EDGE", "edge", "edge", 473_600, 400),
    Gprs("GPRS", "gprs", "gprs", 57_600, 200),
}

/** What the emulator reports: a known preset, values set some other way, or nothing usable. */
sealed interface NetworkThrottleRead {
    data class Known(val throttle: NetworkThrottle) : NetworkThrottleRead

    data class Custom(val downloadBitsPerSecond: Long, val maxLatencyMs: Int) : NetworkThrottleRead

    data class Unreadable(val reason: String) : NetworkThrottleRead
}

private const val EMULATOR_SERIAL_PREFIX = "emulator-"
private val DOWNLOAD = Regex("""download speed:\s+(\d+) bits/s""")
private val MAX_LATENCY = Regex("""maximum latency:\s+(\d+) ms""")

/**
 * Network throttling through the emulator console (`adb emu network speed|delay|status`). Android
 * has no equivalent for physical devices without root, so only emulator serials are supported.
 * Commands are host argv ([AdbOperation.Host]): ddmlib has no console passthrough, so the binary
 * transport runs them.
 */
object NetworkThrottleCommand {

    fun isSupported(serial: DeviceSerial): Boolean = serial.toString().startsWith(EMULATOR_SERIAL_PREFIX)

    fun applyRequests(serial: DeviceSerial, throttle: NetworkThrottle): List<AdbDeviceRequest> = listOf(
        host(serial, "emu", "network", "speed", throttle.speedProfile),
        host(serial, "emu", "network", "delay", throttle.delayProfile),
    )

    fun statusRequest(serial: DeviceSerial): AdbDeviceRequest = host(serial, "emu", "network", "status")

    /** The console answers `OK` or `KO: <reason>`; anything else (or no answer) is a failed write. */
    fun isAccepted(result: AdbTextResult): Boolean =
        result.outcome is AdbOutcome.Completed && result.stdout.lineSequence().any { it.trim() == "OK" }

    fun parseStatus(result: AdbTextResult): NetworkThrottleRead {
        val outcome = result.outcome
        if (outcome !is AdbOutcome.Completed) {
            return NetworkThrottleRead.Unreadable(if (outcome == AdbOutcome.TimedOut) "timed out" else outcome.toString())
        }
        val download = DOWNLOAD.find(result.stdout)?.groupValues?.get(1)?.toLongOrNull()
        val maxLatency = MAX_LATENCY.find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
        if (download == null || maxLatency == null) return NetworkThrottleRead.Unreadable(result.stdout.trim())
        val known = NetworkThrottle.entries.firstOrNull { it.downloadBitsPerSecond == download && it.maxLatencyMs == maxLatency }
        return known?.let(NetworkThrottleRead::Known) ?: NetworkThrottleRead.Custom(download, maxLatency)
    }

    private fun host(serial: DeviceSerial, vararg arguments: String) =
        AdbDeviceRequest(serial = serial, operation = AdbOperation.Host(arguments.toList()))
}
