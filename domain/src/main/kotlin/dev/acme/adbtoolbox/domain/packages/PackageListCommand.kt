package dev.acme.adbtoolbox.domain.packages

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken

private val LINE_SEPARATORS = listOf("\r\n", "\n")

/**
 * The feature-local `pm list packages` command/parser pair (task 021), built only from
 * [ShellToken]s per ADR 0005 — never a raw string. [PackageListScope.User] adds `-3` (third-party
 * packages only); [PackageListScope.All] adds nothing, since plain `pm list packages` already
 * returns the full user+system set (`design/IMPLEMENTATION.md` §4).
 */
object PackageListCommand {

    fun request(serial: DeviceSerial, scope: PackageListScope): AdbDeviceRequest {
        val tokens = buildList {
            add(ShellToken.Literal("pm"))
            add(ShellToken.Literal("list"))
            add(ShellToken.Literal("packages"))
            if (scope == PackageListScope.User) {
                add(ShellToken.Literal("-3"))
            }
        }
        return AdbDeviceRequest(serial = serial, operation = AdbOperation.Shell(AdbShellCommand(tokens)))
    }

    /**
     * Every non-blank line, parsed independently — a `package:`-prefixed line is [AdbParseResult.Parsed]
     * with the trimmed package name; anything else is [AdbParseResult.Malformed] rather than
     * silently dropped or thrown, so one garbled line never aborts the rest of the list.
     */
    fun parse(result: AdbTextResult): List<AdbParseResult<String>> =
        result.stdout
            .split(*LINE_SEPARATORS.toTypedArray())
            .map { it.trim() }
            .filterNot { it.isBlank() }
            .map(::parseLine)

    /**
     * [parse], filtered to successfully parsed names and deduplicated — when the same package name
     * appears more than once, the last occurrence's parse wins (matching
     * [dev.acme.adbtoolbox.domain.device.DeviceListParser.parseDevices]'s precedent), while the
     * name's first-seen position is preserved.
     */
    fun distinctPackageNames(result: AdbTextResult): List<String> {
        val seen = LinkedHashMap<String, String>()
        parse(result).forEach { line ->
            if (line is AdbParseResult.Parsed) {
                seen[line.value] = line.value
            }
        }
        return seen.values.toList()
    }

    private fun parseLine(line: String): AdbParseResult<String> {
        if (!line.startsWith("package:")) {
            return AdbParseResult.Malformed(line, "expected a 'package:' prefixed line")
        }
        val name = line.removePrefix("package:").trim()
        if (name.isBlank()) {
            return AdbParseResult.Malformed(line, "package name was blank")
        }
        return AdbParseResult.Parsed(name)
    }
}
