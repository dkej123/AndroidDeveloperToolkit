package dev.acme.adbtoolbox.domain.wifi

/**
 * A validated `host:port` Wi-Fi pairing/connect endpoint. [render] is the exact text
 * `adb pair`/`adb connect` expect as their address argument; the only way to obtain an instance is
 * [WifiEndpoint.parse], so an invalid address can never reach a command factory (task 039).
 */
data class WifiEndpoint(val host: WifiHost, val port: WifiPort) {
    fun render(): String = "${host.value}:${port.value}"

    companion object {
        /** Splits on the last colon so the host half is validated independently of the port half. */
        fun parse(raw: String): WifiEndpointResult {
            val trimmed = raw.trim()
            val separatorIndex = trimmed.lastIndexOf(':')
            if (separatorIndex <= 0 || separatorIndex == trimmed.length - 1) {
                return WifiEndpointResult.Invalid("Address must be in host:port form")
            }
            val hostResult = WifiHost.parse(trimmed.substring(0, separatorIndex))
            val portResult = WifiPort.parse(trimmed.substring(separatorIndex + 1))
            val invalidReason = (hostResult as? WifiHostResult.Invalid)?.reason
                ?: (portResult as? WifiPortResult.Invalid)?.reason
            if (invalidReason != null) return WifiEndpointResult.Invalid(invalidReason)
            return WifiEndpointResult.Valid(
                WifiEndpoint((hostResult as WifiHostResult.Valid).host, (portResult as WifiPortResult.Valid).port),
            )
        }
    }
}

sealed interface WifiEndpointResult {
    data class Valid(val endpoint: WifiEndpoint) : WifiEndpointResult
    data class Invalid(val reason: String) : WifiEndpointResult
}
