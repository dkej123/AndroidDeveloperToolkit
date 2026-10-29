package io.github.dkej123.devicecockpit.domain.display

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellValue
import io.github.dkej123.devicecockpit.domain.adb.ShellToken

/**
 * Which known TalkBack build is installed: Samsung (One UI) ships its own service; Pixel and
 * practically every other GMS device (Xiaomi, OnePlus, OPPO, Motorola, Sony, Nothing, ...) ship
 * Google's. Devices without either (e.g. Huawei without GMS) have no known component name.
 */
enum class TalkBackVendor { Samsung, Google }

/** Where the commands a TalkBack toggle runs came from, for the UI to name. */
enum class TalkBackProfile { Samsung, Google, Custom }

/**
 * The user's own TalkBack on/off command lines from Settings; `null` means "use the detected
 * vendor's default". Kept normalized by [TalkBackCommand.normalizeCustom].
 */
data class TalkBackCustomCommands(val on: String? = null, val off: String? = null)

/**
 * The resolved device-shell lines a TalkBack toggle runs. [on] is `null` when no known TalkBack is
 * installed and Settings has no custom on-command, so there is nothing sensible to enable. [profile]
 * is `null` in that same situation.
 */
data class TalkBackCommands(val on: String?, val off: String, val profile: TalkBackProfile?)

private const val SERVICES_SETTING = "enabled_accessibility_services"
private const val SAMSUNG_PACKAGE = "com.samsung.android.accessibility.talkback"
private const val GOOGLE_PACKAGE = "com.google.android.marvin.talkback"
private const val SAMSUNG_SERVICE =
    "com.samsung.android.accessibility.talkback/com.samsung.android.marvin.talkback.TalkBackService"
private const val GOOGLE_SERVICE =
    "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
private fun servicesWrite(services: List<String>): String = AdbShellCommand.of(
    ShellToken.Literal("settings put secure $SERVICES_SETTING"),
    ShellToken.Value(ShellValue.of(services.joinToString(":").ifEmpty { "null" })),
).render()

private fun isKnownTalkBack(component: String): Boolean {
    val packageName = component.substringBefore('/')
    val className = component.substringAfter('/').let { if (it.startsWith('.')) packageName + it else it }
    return "$packageName/$className" in listOf(SAMSUNG_SERVICE, GOOGLE_SERVICE)
}

// `adb shell `, optionally `adb -s <serial> shell `, at the start or after `&&`.
private val LEADING_ADB_SHELL = Regex("""^adb\s+(?:-s\s+\S+\s+)?shell\s+""")
private val CHAINED_ADB_SHELL = Regex("""&&\s*adb\s+(?:-s\s+\S+\s+)?shell\s+""")

/**
 * The TalkBack quick toggle. There is no single TalkBack switch in `settings`: enabling it means
 * turning accessibility on and naming the TalkBack service in `enabled_accessibility_services`,
 * and Samsung ships a different service than Google's. Which one applies is detected from the
 * TalkBack packages actually installed (not the manufacturer, which says nothing about ROMs
 * without GMS); Settings may replace either direction with a custom command line.
 *
 * Default commands preserve other enabled accessibility services. The caller must supply a fresh,
 * successfully read service list before writing.
 */
object TalkBackCommand {

    fun detectionRequest(serial: DeviceSerial): AdbDeviceRequest = shell(
        serial,
        AdbShellCommand.of(
            ShellToken.Literal("pm"),
            ShellToken.Literal("list"),
            ShellToken.Literal("packages"),
            ShellToken.Literal("talkback"),
        ),
    )

    /** The installed known TalkBack, preferring Samsung's (One UI) when both are present; `null` when neither is. */
    fun vendorOf(result: AdbTextResult): TalkBackVendor? {
        if (result.outcome !is AdbOutcome.Completed) return null
        val packages = result.stdout.lineSequence().map { it.trim().removePrefix("package:") }.toSet()
        return when {
            SAMSUNG_PACKAGE in packages -> TalkBackVendor.Samsung
            GOOGLE_PACKAGE in packages -> TalkBackVendor.Google
            else -> null
        }
    }

    fun resolve(
        vendor: TalkBackVendor?,
        custom: TalkBackCustomCommands,
        enabledServices: List<String> = emptyList(),
    ): TalkBackCommands {
        fun onLine(service: String): String {
            val services = if (enabledServices.any { sameComponent(it, service) }) enabledServices else enabledServices + service
            return "settings put secure accessibility_enabled 1 && " + servicesWrite(services)
        }
        val remaining = enabledServices.filterNot(::isKnownTalkBack)
        val offLine = servicesWrite(remaining) + " && settings put secure accessibility_enabled " + if (remaining.isEmpty()) "0" else "1"
        val vendorOn = when (vendor) {
            TalkBackVendor.Samsung -> onLine(SAMSUNG_SERVICE)
            TalkBackVendor.Google -> onLine(GOOGLE_SERVICE)
            null -> null
        }
        val vendorProfile = when (vendor) {
            TalkBackVendor.Samsung -> TalkBackProfile.Samsung
            TalkBackVendor.Google -> TalkBackProfile.Google
            null -> null
        }
        return TalkBackCommands(
            on = custom.on ?: vendorOn,
            off = custom.off ?: offLine,
            profile = if (custom.on != null || custom.off != null) TalkBackProfile.Custom else vendorProfile,
        )
    }

    /**
     * Reduces a user-entered command to the line the device shell runs: people paste what they
     * type in a terminal (`adb shell a && adb shell b`), so the `adb [-s serial] shell` prefixes are
     * dropped. Blank means "no custom command" (`null`).
     */
    fun normalizeCustom(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.replace(LEADING_ADB_SHELL, "").replace(CHAINED_ADB_SHELL, "&& ").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Runs [commandLine] — a default from [resolve] or the user's own Settings entry — through the
     * device shell unchanged; it is deliberately a whole shell line (with `&&` chaining), exactly
     * as the user would run it after `adb shell`.
     */
    fun writeRequest(serial: DeviceSerial, commandLine: String): AdbDeviceRequest =
        shell(serial, AdbShellCommand.of(ShellToken.Literal(commandLine)))

    fun readRequest(serial: DeviceSerial): AdbDeviceRequest = shell(
        serial,
        AdbShellCommand.of(
            ShellToken.Literal("settings"),
            ShellToken.Literal("get"),
            ShellToken.Literal("secure"),
            ShellToken.Literal(SERVICES_SETTING),
        ),
    )

    /** Reads the entire list; failed or malformed reads must never be treated as an empty list for a write. */
    fun parseServices(result: AdbTextResult): DisplaySettingRead<List<String>> {
        if (result.stderr.looksLikePermissionDenial()) return DisplaySettingRead.PermissionDenied(result.stderr)
        // ddmlib (the transport inside the IDE) completes without an exit code: only a real non-zero one is a failure.
        if (result.outcome !is AdbOutcome.Completed || (result.outcome.exitCode ?: 0) != 0) {
            return DisplaySettingRead.TransportFailed(result.outcome)
        }
        val text = result.stdout.trim()
        if (text.isEmpty() || text == "null") return DisplaySettingRead.Value(emptyList())
        val services = text.split(':')
        if (services.any { !Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+").matches(it) }) {
            return DisplaySettingRead.Malformed(result.stdout, "Invalid accessibility service list")
        }
        return DisplaySettingRead.Value(services)
    }

    /** On while an enabled service is TalkBack (also supports user-supplied TalkBack builds). */
    fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> = when (val services = parseServices(result)) {
        is DisplaySettingRead.Value -> DisplaySettingRead.Value(services.value.any { it.contains("talkback", ignoreCase = true) })
        is DisplaySettingRead.Malformed -> services
        is DisplaySettingRead.PermissionDenied -> services
        is DisplaySettingRead.TransportFailed -> services
        DisplaySettingRead.NotSet -> DisplaySettingRead.NotSet
    }

    private fun sameComponent(left: String, right: String): Boolean {
        fun expanded(component: String): String {
            val packageName = component.substringBefore('/')
            val className = component.substringAfter('/')
            return packageName + "/" + if (className.startsWith('.')) packageName + className else className
        }
        return expanded(left) == expanded(right)
    }

    private fun shell(serial: DeviceSerial, command: AdbShellCommand) =
        AdbDeviceRequest(serial = serial, operation = AdbOperation.Shell(command))
}
