package dev.acme.adbtoolbox.domain.display

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken

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
private const val OFF_LINE =
    "settings put secure $SERVICES_SETTING null && settings put secure accessibility_enabled 0"

private fun onLine(service: String) =
    "settings put secure accessibility_enabled 1 && settings put secure $SERVICES_SETTING $service"

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
 * Note that the default commands replace the whole enabled-services list, which also turns off
 * any other accessibility service that was on.
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

    fun resolve(vendor: TalkBackVendor?, custom: TalkBackCustomCommands): TalkBackCommands {
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
            off = custom.off ?: OFF_LINE,
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

    /** On while any enabled accessibility service is a TalkBack service (either vendor's). */
    fun parseRead(result: AdbTextResult): DisplaySettingRead<Boolean> {
        if (result.stderr.looksLikePermissionDenial()) return DisplaySettingRead.PermissionDenied(result.stderr)
        if (!result.outcome.reachedDevice()) return DisplaySettingRead.TransportFailed(result.outcome)
        return DisplaySettingRead.Value(result.stdout.contains("talkback", ignoreCase = true))
    }

    private fun shell(serial: DeviceSerial, command: AdbShellCommand) =
        AdbDeviceRequest(serial = serial, operation = AdbOperation.Shell(command))
}
