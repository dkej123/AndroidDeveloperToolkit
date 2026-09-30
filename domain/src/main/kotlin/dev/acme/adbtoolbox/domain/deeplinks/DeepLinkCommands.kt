package dev.acme.adbtoolbox.domain.deeplinks

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue

object DeepLinkCommands {
    private val packageName = Regex("""[A-Za-z0-9_.]+""")
    private val uriScheme = Regex("""[A-Za-z][A-Za-z0-9+.-]*:.*""")

    fun open(serial: DeviceSerial, packageName: String, uri: String): AdbDeviceRequest {
        require(this.packageName.matches(packageName)) { "Invalid package name" }
        require(uriScheme.matches(uri) && uri.none { it.isISOControl() }) { "Invalid URI" }
        fun l(value: String) = ShellToken.Literal(value)
        fun v(value: String) = ShellToken.Value(ShellValue.of(value))
        return AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(
            l("am"), l("start"), l("-W"), l("-a"), v("android.intent.action.VIEW"),
            l("-c"), v("android.intent.category.BROWSABLE"), l("-d"), v(uri), l("-p"), v(packageName),
        )))
    }
}

fun UriPattern.matches(uri: String): Boolean {
    val parsed = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$""").matchEntire(uri) ?: return false
    val scheme = parsed.groupValues[1]
    val authority = parsed.groupValues[2].substringAfterLast('@')
    val host = authority.substringBeforeLast(':', authority).removePrefix("[").removeSuffix("]")
    val port = if (authority.count { it == ':' } == 1) authority.substringAfterLast(':') else ""
    val path = parsed.groupValues[3]
    val query = parsed.groupValues[4]
    val fragment = parsed.groupValues[5]
    if (schemes.isNotEmpty() && schemes.none { it.equals(scheme, ignoreCase = true) }) return false
    if (hosts.isNotEmpty() && hosts.none { expected ->
            if (expected.startsWith("*.")) host.equals(expected.removePrefix("*."), true) || host.endsWith(expected.removePrefix("*"), true)
            else host.equals(expected, true)
        }) return false
    if (ports.isNotEmpty() && port !in ports) return false
    return paths.matchesAny(path) && queries.matchesAny(query) && fragments.matchesAny(fragment)
}

private fun List<UriMatcher>.matchesAny(value: String): Boolean = isEmpty() || any { matcher ->
    when (matcher.kind) {
        UriMatcherKind.PATH_LITERAL, UriMatcherKind.QUERY_LITERAL, UriMatcherKind.FRAGMENT_LITERAL -> value == matcher.value
        UriMatcherKind.PATH_PREFIX, UriMatcherKind.SSP_PREFIX -> value.startsWith(matcher.value)
        UriMatcherKind.PATH_SUFFIX -> value.endsWith(matcher.value)
        UriMatcherKind.PATH_PATTERN, UriMatcherKind.QUERY_PATTERN, UriMatcherKind.FRAGMENT_PATTERN, UriMatcherKind.SSP_PATTERN ->
            Regex.escape(matcher.value).replace("\\.\\*", ".*").toRegex().matches(value)
        UriMatcherKind.PATH_ADVANCED_PATTERN, UriMatcherKind.QUERY_ADVANCED_PATTERN,
        UriMatcherKind.FRAGMENT_ADVANCED_PATTERN, UriMatcherKind.SSP_ADVANCED_PATTERN -> runCatching { Regex(matcher.value).matches(value) }.getOrDefault(false)
        UriMatcherKind.SSP_LITERAL -> value == matcher.value
    }
}
