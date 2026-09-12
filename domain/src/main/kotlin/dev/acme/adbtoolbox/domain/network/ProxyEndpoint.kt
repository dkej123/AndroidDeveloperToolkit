package dev.acme.adbtoolbox.domain.network

/**
 * A validated global-proxy target: a [ProxyHost] and [ProxyPort], both already syntax-checked.
 * [render] is the exact `host:port` text the device command/readback protocol uses — the last
 * colon always separates host from port, which also lets an IPv6 host round-trip without brackets.
 */
data class ProxyEndpoint(val host: ProxyHost, val port: ProxyPort) {
    fun render(): String = "${host.value}:${port.value}"
}
