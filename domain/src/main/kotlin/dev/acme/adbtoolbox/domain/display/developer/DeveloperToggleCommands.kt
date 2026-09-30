package dev.acme.adbtoolbox.domain.display.developer

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.display.DisplaySettingRead
import dev.acme.adbtoolbox.domain.display.looksLikePermissionDenial
import dev.acme.adbtoolbox.domain.display.reachedDevice

/**
 * A Developer-options on/off switch driven by plain shell commands: one read, and the writes that
 * set it. Callers write only when the readback disagrees with the target and always trust a final
 * readback, so a write that toggles rather than sets (SurfaceFlinger's) is still safe.
 */
interface ShellToggleCommand {
    fun readRequest(serial: DeviceSerial): AdbDeviceRequest

    fun writeRequests(serial: DeviceSerial, enabled: Boolean): List<AdbDeviceRequest>

    fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean>
}

/** "Stay awake": `svc power stayon` sets the plug-type mask of `stay_on_while_plugged_in`; any non-zero mask is on. */
object StayAwakeCommand : ShellToggleCommand {
    override fun readRequest(serial: DeviceSerial) = shell(serial, "settings", "get", "global", "stay_on_while_plugged_in")

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) =
        listOf(shell(serial, "svc", "power", "stayon", enabled.toString()))

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> = parseSettingsOutput(result) { value ->
        when {
            value == "null" -> false
            value.toIntOrNull() != null -> value.toInt() != 0
            else -> null
        }
    }
}

/**
 * "Don't keep activities" readback. The value itself is only written by `ActivityManager.setAlwaysFinish`
 * through the on-device helper ([DevOptionsAction.SetAlwaysFinish]): a bare `settings put` updates this
 * setting without the running activity manager ever noticing it.
 */
object AlwaysFinishActivitiesCommand {
    fun readRequest(serial: DeviceSerial) = shell(serial, "settings", "get", "global", "always_finish_activities")

    fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> = parseSettingsOutput(result) { value ->
        when (value) {
            "1" -> true
            "0", "null" -> false
            else -> null
        }
    }
}

/**
 * "Show view updates": HWUI's dirty-region flashing. Apps read the property only when told system
 * properties changed, so each write is followed by the activity manager's `SYSPROPS_TRANSACTION`
 * (`'_SPR'`), which it forwards to every running app — what the Settings app does itself.
 */
object ShowViewUpdatesCommand : ShellToggleCommand {
    private const val PROPERTY = "debug.hwui.show_dirty_regions"
    private const val SYSPROPS_TRANSACTION = "1599295570"

    override fun readRequest(serial: DeviceSerial) = shell(serial, "getprop", PROPERTY)

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) = listOf(
        shell(serial, "setprop", PROPERTY, enabled.toString()),
        shell(serial, "service", "call", "activity", SYSPROPS_TRANSACTION),
    )

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> = parseSettingsOutput(result) { value ->
        when (value) {
            "true" -> true
            "", "false" -> false
            else -> null
        }
    }
}

/**
 * "Show surface updates": SurfaceFlinger's private debug transactions — 1010 reports its debug state
 * (the third word is the show-updates flag, a flash delay on newer builds), 1002 sets it, except that
 * `0` toggles. SurfaceFlinger accepts these only from callers with `HARDWARE_TEST`, which the shell
 * user lacks on production builds, so this works on emulators and `adb root` devices.
 */
object ShowSurfaceUpdatesCommand : ShellToggleCommand {
    const val NEEDS_ROOT_MESSAGE = "Needs adb root — SurfaceFlinger rejects the shell user on this device"
    private val REPLY_WORDS = Regex("""0x[0-9a-fA-F]+:((?:\s+[0-9a-fA-F]{8})+)""")

    override fun readRequest(serial: DeviceSerial) = shell(serial, "service", "call", "SurfaceFlinger", "1010")

    override fun writeRequests(serial: DeviceSerial, enabled: Boolean) =
        listOf(shell(serial, "service", "call", "SurfaceFlinger", "1002", "i32", if (enabled) "1" else "0"))

    override fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> {
        if (!result.outcome.reachedDevice()) return DisplaySettingRead.TransportFailed(result.outcome)
        val output = result.stdout + result.stderr
        if (output.contains("not permitted", ignoreCase = true) || output.looksLikePermissionDenial()) {
            return DisplaySettingRead.PermissionDenied(NEEDS_ROOT_MESSAGE)
        }
        val words = REPLY_WORDS.findAll(result.stdout).flatMap { it.groupValues[1].trim().split(Regex("\\s+")) }.toList()
        if (words.size < 3) return DisplaySettingRead.Malformed(raw = result.stdout, reason = "expected a SurfaceFlinger debug reply")
        return DisplaySettingRead.Value(words[2].toLong(16) != 0L)
    }
}

/** Parses a one-value `settings get`/`getprop` reply with [value]; `null` from [value] means malformed. */
private fun parseSettingsOutput(result: AdbTextResult, value: (String) -> Boolean?): DisplaySettingRead<Boolean> {
    if (!result.outcome.reachedDevice()) return DisplaySettingRead.TransportFailed(result.outcome)
    if (result.stderr.looksLikePermissionDenial()) return DisplaySettingRead.PermissionDenied(result.stderr)
    val parsed = value(result.stdout.trim()) ?: return DisplaySettingRead.Malformed(raw = result.stdout, reason = "unexpected value")
    return DisplaySettingRead.Value(parsed)
}

internal fun shell(serial: DeviceSerial, vararg literals: String): AdbDeviceRequest = AdbDeviceRequest(
    serial = serial,
    operation = AdbOperation.Shell(AdbShellCommand.of(*literals.map { ShellToken.Literal(it) }.toTypedArray())),
)
