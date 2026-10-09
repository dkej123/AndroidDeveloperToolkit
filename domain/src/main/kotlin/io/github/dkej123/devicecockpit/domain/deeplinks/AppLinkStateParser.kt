package io.github.dkej123.devicecockpit.domain.deeplinks

object AppLinkStateParser {
    private val domainLine = Regex("""^\s*([*A-Za-z0-9._-]+):\s*(\S+)\s*$""")

    fun parse(output: String): List<AppLinkVerification> = output.lineSequence().mapNotNull { line ->
        val match = domainLine.matchEntire(line) ?: return@mapNotNull null
        val host = match.groupValues[1]
        if (host.equals("state", true) || '.' !in host) return@mapNotNull null
        val raw = match.groupValues[2]
        val code = raw.toIntOrNull()
        val state = when {
            raw.equals("verified", true) -> DeviceLinkState.VERIFIED
            raw.equals("approved", true) || code == 1 -> DeviceLinkState.APPROVED
            raw.equals("denied", true) || code == 2 -> DeviceLinkState.DENIED
            raw.equals("legacy_failure", true) -> DeviceLinkState.LEGACY_FAILURE
            raw.equals("none", true) || code == 0 -> DeviceLinkState.NONE
            code != null && code >= 1024 -> DeviceLinkState.VENDOR
            else -> DeviceLinkState.NONE
        }
        AppLinkVerification(host, state, code)
    }.toList()
}
