package dev.acme.adbtoolbox.domain.network

/**
 * A syntactically valid global-proxy host: a hostname, IPv4 literal, or IPv6 literal. [ProxyHost]
 * carries no reachability guarantee — only [ProxyHost.parse] validates syntax, and the only way to
 * obtain an instance is through it, so an invalid host can never reach a command factory.
 */
@JvmInline
value class ProxyHost private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val IPV4 = Regex(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$",
        )
        private val HOSTNAME_LABEL = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
        private val IPV6_GROUP = Regex("^[0-9a-fA-F]{1,4}$")

        /** Trims whitespace, rejects embedded whitespace/control characters, then validates syntax. */
        fun parse(raw: String): ProxyHostResult {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ProxyHostResult.Invalid("Host must not be empty")
            if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
                return ProxyHostResult.Invalid("Host must not contain whitespace or control characters")
            }
            return when {
                IPV4.matches(trimmed) -> ProxyHostResult.Valid(ProxyHost(trimmed))
                isIpv6(trimmed) -> ProxyHostResult.Valid(ProxyHost(trimmed))
                isHostname(trimmed) -> ProxyHostResult.Valid(ProxyHost(trimmed.lowercase()))
                else -> ProxyHostResult.Invalid("Host must be a valid hostname or IPv4/IPv6 literal")
            }
        }

        private fun isHostname(value: String): Boolean {
            if (value.length > 253) return false
            val labels = value.split(".")
            if (labels.isEmpty() || labels.any { !HOSTNAME_LABEL.matches(it) }) return false
            // A purely-numeric final label (e.g. "999.0.4.117") is a malformed IPv4 literal, not a
            // hostname (DNS TLDs are never all-digit) — reject rather than silently accepting it.
            return labels.last().any { !it.isDigit() }
        }

        private fun isIpv6(value: String): Boolean {
            if (!value.contains(':')) return false
            val compressionCount = Regex("::").findAll(value).count()
            if (compressionCount > 1) return false
            val parts = if (compressionCount == 1) value.split("::") else listOf(value)
            if (parts.size > 2) return false

            val hasCompression = compressionCount == 1
            val leftNonEmpty = parts[0].split(":").filter { it.isNotEmpty() }
            val rightNonEmpty = if (parts.size == 2) parts[1].split(":").filter { it.isNotEmpty() } else emptyList()

            if (!hasCompression) {
                val groups = value.split(":")
                return groups.size == 8 && groups.all { IPV6_GROUP.matches(it) }
            }

            if (!leftNonEmpty.all { IPV6_GROUP.matches(it) }) return false
            if (!rightNonEmpty.all { IPV6_GROUP.matches(it) }) return false
            return leftNonEmpty.size + rightNonEmpty.size < 8
        }
    }
}

sealed interface ProxyHostResult {
    data class Valid(val host: ProxyHost) : ProxyHostResult
    data class Invalid(val reason: String) : ProxyHostResult
}
