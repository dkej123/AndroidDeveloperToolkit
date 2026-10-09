package io.github.dkej123.devicecockpit.domain.location

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial

/** A GPS position in decimal degrees, validated by [of]. */
data class GeoPoint private constructor(val latitude: Double, val longitude: Double) {
    companion object {
        fun of(latitude: Double, longitude: Double): GeoPoint? =
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0 && !latitude.isNaN() && !longitude.isNaN()) {
                GeoPoint(latitude, longitude)
            } else {
                null
            }

        /** "52.2297, 21.0122" or "52.2297 21.0122": latitude first, as maps show it. */
        fun parse(text: String): GeoPoint? {
            val numbers = text.split(',', ' ', ';').map(String::trim).filter(String::isNotEmpty)
            if (numbers.size != 2) return null
            val latitude = numbers[0].toDoubleOrNull() ?: return null
            val longitude = numbers[1].toDoubleOrNull() ?: return null
            return of(latitude, longitude)
        }
    }
}

data class CityPreset(val name: String, val point: GeoPoint)

/**
 * The emulator's GPS fix through its console (`adb emu geo fix <longitude> <latitude>` — longitude
 * first). Emulator-only, like network throttling: physical devices are refused up front.
 *
 * Ported from Oh My Android, MIT — `Sources/Features/LocationFeatures.swift`.
 */
object EmulatorLocationCommand {
    private const val EMULATOR_SERIAL_PREFIX = "emulator-"

    /** The design's presets (§3c), in its order. */
    val cities: List<CityPreset> = listOf(
        city("Warsaw", 52.2297, 21.0122),
        city("London", 51.5072, -0.1276),
        city("New York", 40.7128, -74.0060),
        city("San Francisco", 37.7749, -122.4194),
        city("Tokyo", 35.6895, 139.6917),
        city("Sydney", -33.8688, 151.2093),
        city("Stockholm", 59.3293, 18.0686),
    )

    fun isSupported(serial: DeviceSerial): Boolean = serial.toString().startsWith(EMULATOR_SERIAL_PREFIX)

    fun request(serial: DeviceSerial, point: GeoPoint): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Host(listOf("emu", "geo", "fix", point.longitude.toString(), point.latitude.toString())),
    )

    /** The console answers `OK` or `KO: <reason>`. */
    fun isAccepted(result: AdbTextResult): Boolean =
        result.outcome is AdbOutcome.Completed && result.stdout.lineSequence().any { it.trim() == "OK" }

    private fun city(name: String, latitude: Double, longitude: Double) = CityPreset(name, requireNotNull(GeoPoint.of(latitude, longitude)))
}
