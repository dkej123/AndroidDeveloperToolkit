package dev.acme.adbtoolbox.domain.wifi

/**
 * A syntactically valid Wi-Fi pairing/connect host: a hostname or an IPv4 literal. Carries no
 * reachability guarantee — only [WifiHost.parse] validates syntax, and the only way to obtain an
 * instance is through it, so an invalid host can never reach a command factory (task 039).
 */
@JvmInline
value class WifiHost private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val IPV4 = Regex(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$",
        )
        private val HOSTNAME_LABEL = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

        /** Trims whitespace, rejects embedded whitespace/control characters, then validates syntax. */
        fun parse(raw: String): WifiHostResult {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return WifiHostResult.Invalid("Host must not be empty")
            if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
                return WifiHostResult.Invalid("Host must not contain whitespace or control characters")
            }
            return when {
                IPV4.matches(trimmed) -> WifiHostResult.Valid(WifiHost(trimmed))
                isHostname(trimmed) -> WifiHostResult.Valid(WifiHost(trimmed.lowercase()))
                else -> WifiHostResult.Invalid("Host must be a valid hostname or IPv4 address")
            }
        }

        private fun isHostname(value: String): Boolean {
            if (value.length > 253) return false
            val labels = value.split(".")
            if (labels.isEmpty() || labels.any { !HOSTNAME_LABEL.matches(it) }) return false
            // A purely-numeric final label is a malformed IPv4 literal, not a hostname (DNS TLDs
            // are never all-digit) — reject rather than silently accepting it.
            return labels.last().any { !it.isDigit() }
        }
    }
}

sealed interface WifiHostResult {
    data class Valid(val host: WifiHost) : WifiHostResult
    data class Invalid(val reason: String) : WifiHostResult
}
