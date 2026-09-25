package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

/** What the app-info helper reports for one installed package. [label] is `null` when blank. */
data class AppInfo(
    val packageName: String,
    val label: String?,
    val isDebuggable: Boolean,
    val icon: AppIcon?,
)

/** One parsed line of the app-info helper's output (see [AppInfoCommand]). */
sealed interface AppInfoLine {
    data object Header : AppInfoLine

    data object End : AppInfoLine

    data class Found(val info: AppInfo) : AppInfoLine

    data class Missing(val packageName: String) : AppInfoLine

    data class Malformed(val line: String, val reason: String) : AppInfoLine
}

private const val HEADER = "ADBTOOLBOX-APPINFO 1"
private const val MAIN_CLASS = "dev.acme.adbtoolbox.devicehelper.AppInfoMain"
private const val PRESENT = "present"
private val PLAIN_TOKEN = Regex("[A-Za-z0-9._-]+")
private val CONTROL_RUNS = Regex("""[\p{Cntrl}\s]+""")

/**
 * The command/parser set for the on-device app-info helper (ADR 0007): `dumpsys package` exposes
 * neither application labels nor icons, so a small dex (`adapters-adb/src/deviceHelper`) is pushed
 * to the device and run by `app_process` as the shell user, where it asks `PackageManager` for
 * each package's label, debuggable flag and a rendered launcher-icon PNG.
 *
 * The helper prints [HEADER], then one tab-separated record per package — `P`, package, `d`/`-`,
 * base64 label, base64 PNG or `-`; or `M`, package for one that is not installed — then `END`.
 */
@OptIn(ExperimentalEncodingApi::class)
object AppInfoCommand {

    /** The helper's push target. [helperVersion] is a content hash, so a new build never reuses a stale jar. */
    fun remotePath(helperVersion: String): String {
        require(PLAIN_TOKEN.matches(helperVersion)) { "helper version must be a plain token: '$helperVersion'" }
        return "/data/local/tmp/adb-toolbox-app-info-$helperVersion.jar"
    }

    fun presenceRequest(serial: DeviceSerial, remotePath: String): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("test"),
                ShellToken.Literal("-f"),
                ShellToken.Value(ShellValue.of(remotePath)),
                ShellToken.Literal("&&"),
                ShellToken.Literal("echo"),
                ShellToken.Literal(PRESENT),
            ),
        ),
    )

    fun isPresent(result: AdbTextResult): Boolean =
        result.outcome is AdbOutcome.Completed && result.stdout.trim() == PRESENT

    fun pushRequest(serial: DeviceSerial, localPath: String, remotePath: String): AdbDeviceRequest =
        AdbDeviceRequest(serial = serial, operation = AdbOperation.Host(listOf("push", localPath, remotePath)))

    /**
     * Runs the helper for [packages], or for every installed application when [packages] is empty.
     * [remotePath] comes from [remotePath], so it is safe as the literal `CLASSPATH=` assignment.
     */
    fun request(
        serial: DeviceSerial,
        remotePath: String,
        iconSizePx: Int,
        packages: List<String>,
    ): AdbDeviceRequest {
        val tokens = buildList {
            add(ShellToken.Literal("CLASSPATH=$remotePath"))
            add(ShellToken.Literal("app_process"))
            add(ShellToken.Literal("/"))
            add(ShellToken.Literal(MAIN_CLASS))
            add(ShellToken.Literal(iconSizePx.toString()))
            packages.forEach { add(ShellToken.Value(ShellValue.of(it))) }
        }
        return AdbDeviceRequest(serial = serial, operation = AdbOperation.Shell(AdbShellCommand(tokens)), timeout = 120.seconds)
    }

    fun parseLine(rawLine: String): AppInfoLine {
        val line = rawLine.trimEnd('\r', '\n')
        if (line == HEADER) return AppInfoLine.Header
        if (line == "END") return AppInfoLine.End
        val fields = line.split('\t')
        return when {
            fields.size == 2 && fields[0] == "M" && fields[1].isNotBlank() -> AppInfoLine.Missing(fields[1])
            fields.size == 5 && fields[0] == "P" && fields[1].isNotBlank() -> parseFound(line, fields)
            else -> AppInfoLine.Malformed(line, "not an app-info record")
        }
    }

    private fun parseFound(line: String, fields: List<String>): AppInfoLine {
        val isDebuggable = when (fields[2]) {
            "d" -> true
            "-" -> false
            else -> return AppInfoLine.Malformed(line, "unknown debuggable flag '${fields[2]}'")
        }
        val label = decode(fields[3])?.decodeToString()
            ?: return AppInfoLine.Malformed(line, "label is not valid base64")
        val icon = fields[4].takeUnless { it == "-" }?.let(::decode)?.let(::AppIcon)
        return AppInfoLine.Found(
            AppInfo(
                packageName = fields[1],
                label = label.replace(CONTROL_RUNS, " ").trim().takeIf { it.isNotEmpty() },
                isDebuggable = isDebuggable,
                icon = icon,
            ),
        )
    }

    private fun decode(text: String): ByteArray? = try {
        Base64.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
