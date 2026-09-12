package dev.acme.adbtoolbox.domain.network

/**
 * A validated global-proxy port in the 1-65535 range. The only way to obtain an instance is
 * through [ProxyPort.parse], so an out-of-range port can never reach a command factory.
 */
@JvmInline
value class ProxyPort private constructor(val value: Int) {

    override fun toString(): String = value.toString()

    companion object {
        private val RANGE = 1..65535

        fun parse(raw: Int): ProxyPortResult =
            if (raw in RANGE) ProxyPortResult.Valid(ProxyPort(raw)) else ProxyPortResult.Invalid("Port must be 1-65535")

        fun parse(raw: String): ProxyPortResult {
            val trimmed = raw.trim()
            val value = trimmed.toIntOrNull() ?: return ProxyPortResult.Invalid("Port must be a number")
            return parse(value)
        }
    }
}

sealed interface ProxyPortResult {
    data class Valid(val port: ProxyPort) : ProxyPortResult
    data class Invalid(val reason: String) : ProxyPortResult
}
