package dev.acme.adbtoolbox.domain.wifi

/**
 * A validated Wi-Fi pairing/connect port in the 1-65535 range. The only way to obtain an instance
 * is through [WifiPort.parse], so an out-of-range port can never reach a command factory.
 */
@JvmInline
value class WifiPort private constructor(val value: Int) {

    override fun toString(): String = value.toString()

    companion object {
        private val RANGE = 1..65535

        fun parse(raw: String): WifiPortResult {
            val trimmed = raw.trim()
            val value = trimmed.toIntOrNull() ?: return WifiPortResult.Invalid("Port must be a number")
            return if (value in RANGE) WifiPortResult.Valid(WifiPort(value)) else WifiPortResult.Invalid("Port must be 1-65535")
        }
    }
}

sealed interface WifiPortResult {
    data class Valid(val port: WifiPort) : WifiPortResult
    data class Invalid(val reason: String) : WifiPortResult
}
