package io.github.dkej123.devicecockpit.domain.display.toggles

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.display.DisplaySettingRead
import io.github.dkej123.devicecockpit.domain.display.looksLikePermissionDenial

/**
 * An on/off device setting driven by shell commands (task 059): one read, the writes that set it,
 * and the parser. Callers write only when the readback disagrees and trust a final readback.
 */
interface DeviceSettingToggleCommand {
    fun readRequest(serial: DeviceSerial): AdbDeviceRequest

    fun writeRequests(serial: DeviceSerial, enabled: Boolean): List<AdbDeviceRequest>

    fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean>
}

/** The activity manager's `SYSPROPS_TRANSACTION` (`'_SPR'`): running apps re-read `debug.*` properties, as Settings does. */
private const val SYSPROPS_TRANSACTION = "1599295570"

/**
 * A rendering debug overlay backed by a `debug.*` property, applied live. Unset or `false` is off;
 * [isOn] decides which other values count as on.
 *
 * Ported from Oh My Android, MIT — `Sources/Features/GenericFeatures.swift` (`DebugPropertyToggleFeature`).
 */
open class DebugPropertyToggleCommand(
    private val property: String,
    private val onValue: String,
    private val isOn: (String) -> Boolean = { it != "false" },
) : DeviceSettingToggleCommand {
    override fun readRequest(serial: DeviceSerial) = shell(serial, "getprop", property)

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) = listOf(
        shell(serial, "setprop", property, if (enabled) onValue else "false"),
        shell(serial, "service", "call", "activity", SYSPROPS_TRANSACTION),
    )

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> =
        parseSingleValue(result) { value -> value.isNotEmpty() && isOn(value) }
}

object ShowLayoutBoundsCommand : DebugPropertyToggleCommand("debug.layout", "true")

object GpuOverdrawCommand : DebugPropertyToggleCommand("debug.hwui.overdraw", "show")

/** `true` only writes to `dumpsys gfxinfo`; just the `visual_*` values draw on screen. */
object GpuProfileBarsCommand : DebugPropertyToggleCommand("debug.hwui.profile", "visual_bars", { it.startsWith("visual") })

/**
 * A `settings put <namespace> <key> 1|0` switch; an unset key (`null`) is off.
 *
 * Ported from Oh My Android, MIT — `Sources/Features/GenericFeatures.swift` (`SettingToggleFeature`).
 */
open class SettingToggleCommand(private val namespace: String, private val key: String) : DeviceSettingToggleCommand {
    override fun readRequest(serial: DeviceSerial) = shell(serial, "settings", "get", namespace, key)

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) =
        listOf(shell(serial, "settings", "put", namespace, key, if (enabled) "1" else "0"))

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> = parseSingleValue(result) { value ->
        when (value) {
            "1" -> true
            "0", "null" -> false
            else -> null
        }
    }
}

object PointerLocationCommand : SettingToggleCommand("system", "pointer_location")

object InvertColorsCommand : SettingToggleCommand("secure", "accessibility_display_inversion_enabled")

/**
 * "Bold text": `font_weight_adjustment` (300 = bold), which only Android 12 (API 31) and later
 * honour — older releases report [DisplaySettingRead.NotSet] so the tile shows "n/a".
 */
object BoldTextCommand : DeviceSettingToggleCommand {
    private const val MIN_SDK = 31

    override fun readRequest(serial: DeviceSerial) =
        shell(serial, "getprop", "ro.build.version.sdk", ";", "settings", "get", "secure", "font_weight_adjustment")

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) =
        listOf(shell(serial, "settings", "put", "secure", "font_weight_adjustment", if (enabled) "300" else "0"))

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> {
        failure(result)?.let { return it }
        val lines = result.stdout.lines().map(String::trim).filter(String::isNotEmpty)
        val sdk = lines.firstOrNull()?.toIntOrNull()
            ?: return DisplaySettingRead.Malformed(result.stdout, "expected the SDK level first")
        if (sdk < MIN_SDK) return DisplaySettingRead.NotSet
        val value = lines.getOrNull(1) ?: "null"
        if (value == "null") return DisplaySettingRead.Value(false)
        val weight = value.toIntOrNull() ?: return DisplaySettingRead.Malformed(result.stdout, "expected a font weight")
        return DisplaySettingRead.Value(weight > 0)
    }
}

/** Airplane mode through the connectivity service (`enabled` / `disabled`), as Settings switches it. */
object AirplaneModeCommand : DeviceSettingToggleCommand {
    override fun readRequest(serial: DeviceSerial) = shell(serial, "cmd", "connectivity", "airplane-mode")

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) =
        listOf(shell(serial, "cmd", "connectivity", "airplane-mode", if (enabled) "enable" else "disable"))

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> {
        if (result.stdout.isBlank() && result.stderr.contains("can't find service", ignoreCase = true)) return DisplaySettingRead.NotSet
        return parseSingleValue(result) { value ->
            when (value) {
                "enabled" -> true
                "disabled" -> false
                else -> null
            }
        }
    }
}

/** A radio switched with `svc <service> enable|disable` and read from a global setting (non-zero = on). */
open class SvcRadioCommand(private val service: String, private val setting: String) : DeviceSettingToggleCommand {
    override fun readRequest(serial: DeviceSerial) = shell(serial, "settings", "get", "global", setting)

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) =
        listOf(shell(serial, "svc", service, if (enabled) "enable" else "disable"))

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> = parseSingleValue(result) { value ->
        when {
            value == "null" -> false
            value.toIntOrNull() != null -> value.toInt() != 0
            else -> null
        }
    }
}

object WifiCommand : SvcRadioCommand("wifi", "wifi_on")

/** Mobile data; a device without a cellular radio reads [DisplaySettingRead.NotSet] ("n/a"). */
object MobileDataCommand : SvcRadioCommand("data", "mobile_data") {
    override fun readRequest(serial: DeviceSerial) = shell(
        serial, "cmd", "package", "has-feature", "android.hardware.telephony", ";", "settings", "get", "global", "mobile_data",
    )

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> {
        val lines = result.stdout.lines().map(String::trim).filter(String::isNotEmpty)
        if (result.outcome is AdbOutcome.Completed && lines.firstOrNull() == "false") return DisplaySettingRead.NotSet
        val setting = if (lines.firstOrNull() == "true") lines.drop(1) else lines
        return super.parseRead(result.copy(stdout = setting.joinToString("\n")))
    }
}

/** How the screen rotates: following the sensor, or locked. */
enum class ScreenRotation { Auto, Portrait, Landscape }

/**
 * Screen rotation from `accelerometer_rotation` (auto-rotate) and `user_rotation` (0–3 quarter
 * turns, odd = landscape) when locked.
 */
object ScreenRotationCommand {
    fun readRequest(serial: DeviceSerial) = shell(
        serial, "settings", "get", "system", "accelerometer_rotation", ";", "settings", "get", "system", "user_rotation",
    )

    fun writeRequests(serial: DeviceSerial, rotation: ScreenRotation): List<AdbDeviceRequest> = when (rotation) {
        ScreenRotation.Auto -> listOf(shell(serial, "settings", "put", "system", "accelerometer_rotation", "1"))
        else -> listOf(
            shell(serial, "settings", "put", "system", "accelerometer_rotation", "0"),
            shell(serial, "settings", "put", "system", "user_rotation", if (rotation == ScreenRotation.Landscape) "1" else "0"),
        )
    }

    fun parseRead(result: AdbTextResult): DisplaySettingRead<ScreenRotation> {
        failure(result)?.let { return it }
        val lines = result.stdout.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size < 2) return DisplaySettingRead.Malformed(result.stdout, "expected two settings")
        val auto = lines[0].takeIf { it != "null" }?.toIntOrNull() ?: if (lines[0] == "null") 0 else null
        val rotation = lines[1].takeIf { it != "null" }?.toIntOrNull() ?: if (lines[1] == "null") 0 else null
        if (auto == null || rotation == null) return DisplaySettingRead.Malformed(result.stdout, "expected numbers")
        return DisplaySettingRead.Value(
            when {
                auto != 0 -> ScreenRotation.Auto
                rotation % 2 == 1 -> ScreenRotation.Landscape
                else -> ScreenRotation.Portrait
            },
        )
    }
}

private fun failure(result: AdbTextResult): DisplaySettingRead<Nothing>? = when {
    result.outcome !is AdbOutcome.Completed -> DisplaySettingRead.TransportFailed(result.outcome)
    result.stderr.looksLikePermissionDenial() -> DisplaySettingRead.PermissionDenied(result.stderr)
    else -> null
}

/** One-value `settings get` / `getprop` / `cmd` reply; `null` from [value] means unexpected output. */
private fun parseSingleValue(result: AdbTextResult, value: (String) -> Boolean?): DisplaySettingRead<Boolean> {
    failure(result)?.let { return it }
    val parsed = value(result.stdout.trim()) ?: return DisplaySettingRead.Malformed(result.stdout, "unexpected value")
    return DisplaySettingRead.Value(parsed)
}

private fun shell(serial: DeviceSerial, vararg literals: String) = AdbDeviceRequest(
    serial = serial,
    operation = AdbOperation.Shell(AdbShellCommand.of(*literals.map { ShellToken.Literal(it) }.toTypedArray())),
)
