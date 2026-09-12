package dev.acme.adbtoolbox.domain.devicefacts

import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private fun lines(raw: String): List<String> =
    raw.split("\r\n", "\n").map { it.trim() }.filterNot { it.isBlank() }

/**
 * Pure parsing of each [DeviceFactId]'s raw shell stdout into a [DeviceFactValue] (task 015). No
 * I/O — testable purely from recorded fixtures (normal, partial, malformed, permission-denied,
 * LF/CRLF, and OEM-variant output). Every parser returns [AdbParseResult.Malformed] rather than
 * throwing on output it does not recognize, per adb-development's "handle malformed/unexpected
 * output without crashing."
 */
object DeviceFactsParsers {

    fun parseAndroidVersion(raw: String): AdbParseResult<DeviceFactValue.AndroidVersion> {
        val rows = lines(raw)
        val release = rows.getOrNull(0)
        val sdkRaw = rows.getOrNull(1)
        val sdk = sdkRaw?.toIntOrNull()
        if (release.isNullOrBlank() || sdk == null) {
            return AdbParseResult.Malformed(raw, "expected a release line followed by a numeric SDK line")
        }
        return AdbParseResult.Parsed(DeviceFactValue.AndroidVersion(release, sdk))
    }

    /** `wm size` prints `Physical size: WxH` and, when overridden, an additional `Override size: WxH` line. */
    fun parseResolution(raw: String): AdbParseResult<DeviceFactValue.Resolution> {
        val override = RESOLUTION_LINE.find(raw.lineSequence().firstOrNull { it.contains("Override size") } ?: "")
        val physical = RESOLUTION_LINE.find(raw.lineSequence().firstOrNull { it.contains("Physical size") } ?: "")
        val match = override ?: physical
            ?: return AdbParseResult.Malformed(raw, "no 'Physical size: WxH' or 'Override size: WxH' line found")
        val width = match.groupValues[1].toIntOrNull()
        val height = match.groupValues[2].toIntOrNull()
        if (width == null || height == null) {
            return AdbParseResult.Malformed(raw, "size line did not contain two numeric dimensions")
        }
        return AdbParseResult.Parsed(DeviceFactValue.Resolution(width, height))
    }

    /** `wm density` prints `Physical density: N` and, when overridden, an additional `Override density: N` line. */
    fun parseDensity(raw: String): AdbParseResult<DeviceFactValue.Density> {
        val override = DENSITY_LINE.find(raw.lineSequence().firstOrNull { it.contains("Override density") } ?: "")
        val physical = DENSITY_LINE.find(raw.lineSequence().firstOrNull { it.contains("Physical density") } ?: "")
        val match = override ?: physical
            ?: return AdbParseResult.Malformed(raw, "no 'Physical density: N' or 'Override density: N' line found")
        val dpi = match.groupValues[1].toIntOrNull()
            ?: return AdbParseResult.Malformed(raw, "density line did not contain a numeric dpi")
        return AdbParseResult.Parsed(DeviceFactValue.Density(dpi))
    }

    /**
     * `dumpsys battery` prints a `level:`/`scale:` pair (percent = level, scale is normally 100 but
     * not assumed to be) and a numeric `status:` (2 = charging, 5 = full — both rendered as
     * charging).
     */
    fun parseBattery(raw: String): AdbParseResult<DeviceFactValue.Battery> {
        val level = batteryField("level").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val scale = batteryField("scale").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 100
        val status = batteryField("status").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        if (level == null || scale <= 0 || status == null) {
            return AdbParseResult.Malformed(raw, "expected numeric 'level:' and 'status:' fields")
        }
        val percent = ((level * 100) / scale).coerceIn(0, 100)
        val charging = status == BATTERY_STATUS_CHARGING || status == BATTERY_STATUS_FULL
        return AdbParseResult.Parsed(DeviceFactValue.Battery(percent, charging))
    }

    fun parseAbi(raw: String): AdbParseResult<DeviceFactValue.Abi> {
        val value = lines(raw).firstOrNull()
        if (value.isNullOrBlank()) {
            return AdbParseResult.Malformed(raw, "expected a single non-blank ABI line")
        }
        return AdbParseResult.Parsed(DeviceFactValue.Abi(value))
    }

    /**
     * `uptime` normally prints `... up [N day(s), ]H:MM, N users, load average: ...` (also seen
     * with just minutes, e.g. `up 5 min,`, on a freshly booted device). Both shapes are supported;
     * anything else is [AdbParseResult.Malformed].
     */
    fun parseUptime(raw: String): AdbParseResult<DeviceFactValue.Uptime> {
        val hourMinute = UPTIME_HOUR_MINUTE.find(raw)
        if (hourMinute != null) {
            val days = hourMinute.groupValues[1].toIntOrNull() ?: 0
            val hours = hourMinute.groupValues[2].toIntOrNull() ?: 0
            val minutes = hourMinute.groupValues[3].toIntOrNull() ?: 0
            return AdbParseResult.Parsed(DeviceFactValue.Uptime(days.days + hours.hours + minutes.minutes))
        }
        val minuteOnly = UPTIME_MINUTE_ONLY.find(raw)
        if (minuteOnly != null) {
            val minutes = minuteOnly.groupValues[1].toIntOrNull() ?: 0
            return AdbParseResult.Parsed(DeviceFactValue.Uptime(minutes.minutes))
        }
        return AdbParseResult.Malformed(raw, "no recognizable 'up ...' uptime clause found")
    }

    private val RESOLUTION_LINE = Regex("""(\d+)x(\d+)""")
    private val DENSITY_LINE = Regex(""":\s*(\d+)""")
    private fun batteryField(name: String) = Regex("""(?m)^\s*$name:\s*(-?\d+)""")
    private const val BATTERY_STATUS_CHARGING = 2
    private const val BATTERY_STATUS_FULL = 5
    private val UPTIME_HOUR_MINUTE = Regex("""up\s+(?:(\d+)\s*days?,\s*)?(\d{1,3}):(\d{2})""")
    private val UPTIME_MINUTE_ONLY = Regex("""up\s+(\d+)\s*min""")
}
