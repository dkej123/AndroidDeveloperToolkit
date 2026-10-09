package io.github.dkej123.devicecockpit.domain.packages

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbParseResult
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue

/**
 * The label/debuggable metadata one `dumpsys package <pkg>` call yields. [label] is `null` when
 * the field could not be found — the caller applies the package-name fallback (ADR 0007), never
 * this parser. [isDebuggable] is `null` when the flags field itself could not be found (genuinely
 * unknown), distinct from a present flags field lacking `DEBUGGABLE` (confidently `false`).
 */
data class PackageMetadata(val label: String?, val isDebuggable: Boolean?)

/**
 * The feature-local `dumpsys package <pkg>` command/parser pair (task 021): one call per package
 * resolves both the application label and the debuggable flag per ADR 0007's approved strategy
 * ("dumpsys package <pkg> parsing ... versionName/applicationLabel-style fields already surfaced
 * by that dump"), rather than a second on-device query per package.
 */
object PackageMetadataCommand {

    private val LABEL_LINE = Regex("""(?m)^\s*applicationLabel=(.+)$""")
    private val FLAGS_LINE = Regex("""(?m)^\s*flags=\[(.*?)]""")
    private val WHITESPACE = Regex("""\s+""")

    fun request(serial: DeviceSerial, packageName: String): AdbDeviceRequest = AdbDeviceRequest(
        serial = serial,
        operation = AdbOperation.Shell(
            AdbShellCommand.of(
                ShellToken.Literal("dumpsys"),
                ShellToken.Literal("package"),
                ShellToken.Value(ShellValue.of(packageName)),
            ),
        ),
    )

    /**
     * Blank output (the device produced nothing at all — e.g. the package vanished mid-scan) is
     * the only [AdbParseResult.Malformed] case; a well-formed dump missing one or both fields is
     * still [AdbParseResult.Parsed] with the missing field(s) `null`, per ADR 0007's "never a
     * blank/crashing row" — partial metadata is not a parse failure.
     */
    fun parse(packageName: String, result: AdbTextResult): AdbParseResult<PackageMetadata> {
        if (result.stdout.isBlank()) {
            return AdbParseResult.Malformed(result.stdout, "empty dumpsys package output for $packageName")
        }

        val label = LABEL_LINE.find(result.stdout)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val flags = FLAGS_LINE.find(result.stdout)?.groupValues?.get(1)
        val isDebuggable = flags?.split(WHITESPACE)
            ?.filterNot { it.isBlank() }
            ?.any { it == "DEBUGGABLE" }

        return AdbParseResult.Parsed(PackageMetadata(label = label, isDebuggable = isDebuggable))
    }
}
