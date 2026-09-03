package dev.acme.adbtoolbox.domain.device

import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial

private val WHITESPACE = Regex("""\s+""")

/**
 * Pure parsing of `adb devices -l` stdout (ADR 0005's server-scoped `AdbServerRequest`) into
 * [Device] values — no I/O, so it is testable purely from recorded fixtures.
 *
 * Lines that are not device rows at all (the `List of devices attached` header, ADB daemon startup
 * chatter such as `* daemon not running; starting now at tcp:5037`) are silently skipped, not
 * surfaced as [AdbParseResult.Malformed] — they are not malformed device rows, they are simply not
 * device rows. A line that looks like a device row but is missing its serial/state columns is
 * [AdbParseResult.Malformed] rather than thrown, so one garbled line never aborts parsing the rest
 * of the output. An unrecognized state token is parsed as [DeviceConnectionState.Unknown] — never a
 * crash and never silently dropped (adb-development: "handle ... malformed/unexpected output
 * without crashing").
 */
object DeviceListParser {

    /**
     * Every non-ignored line, parsed independently — including duplicate serials and malformed
     * lines exactly as they appeared, so fixture tests can assert on each line's outcome. Callers
     * that want the deduplicated device list an observable repository should show use
     * [parseDevices].
     */
    fun parse(rawOutput: String): List<AdbParseResult<Device>> =
        rawOutput
            .split("\r\n", "\n")
            .map { it.trim() }
            .filterNot { it.isBlank() }
            .filterNot(::isIgnoredLine)
            .map(::parseLine)

    /**
     * [parse], filtered to successfully parsed devices and deduplicated by [DeviceSerial] — when
     * the same serial appears more than once in one `adb devices -l` output, the last occurrence
     * wins (matching the CLI's own behavior of printing a device's latest known state), while the
     * device's original first-seen position is preserved.
     */
    fun parseDevices(rawOutput: String): List<Device> {
        val bySerial = LinkedHashMap<DeviceSerial, Device>()
        parse(rawOutput).forEach { result ->
            if (result is AdbParseResult.Parsed) {
                bySerial[result.value.serial] = result.value
            }
        }
        return bySerial.values.toList()
    }

    private fun isIgnoredLine(line: String): Boolean =
        line == "List of devices attached" || line.startsWith("*")

    private fun parseLine(line: String): AdbParseResult<Device> {
        val tokens = line.split(WHITESPACE)
        if (tokens.size < 2) {
            return AdbParseResult.Malformed(line, "expected at least a serial and a state column")
        }

        val serial = try {
            DeviceSerial.of(tokens[0])
        } catch (e: IllegalArgumentException) {
            return AdbParseResult.Malformed(line, "invalid device serial: ${e.message}")
        }

        val (state, fieldsStartIndex) = resolveState(tokens)
        val fields = tokens.drop(fieldsStartIndex).mapNotNull(::keyValue).toMap()

        return AdbParseResult.Parsed(
            Device(
                serial = serial,
                state = state,
                product = fields["product"],
                model = fields["model"],
                device = fields["device"],
                transportId = fields["transport_id"],
            ),
        )
    }

    /**
     * Real adb prints `no permissions` as two whitespace-separated tokens, sometimes followed by a
     * free-text hint ("(user in plugdev group?); see [...]") that must not be mistaken for
     * `key:value` fields — [keyValue] already ignores any token without a `:` separator, so those
     * hint tokens are silently skipped rather than misparsed.
     */
    private fun resolveState(tokens: List<String>): Pair<DeviceConnectionState, Int> {
        if (tokens[1] == "no" && tokens.getOrNull(2) == "permissions") {
            return DeviceConnectionState.NoPermissions to 3
        }
        return parseState(tokens[1]) to 2
    }

    private fun parseState(token: String): DeviceConnectionState = when (token) {
        "device" -> DeviceConnectionState.Online
        "offline" -> DeviceConnectionState.Offline
        "unauthorized" -> DeviceConnectionState.Unauthorized
        "authorizing" -> DeviceConnectionState.Authorizing
        else -> DeviceConnectionState.Unknown(token)
    }

    private fun keyValue(token: String): Pair<String, String>? {
        val separator = token.indexOf(':')
        if (separator <= 0 || separator == token.lastIndex) return null
        return token.substring(0, separator) to token.substring(separator + 1)
    }
}
