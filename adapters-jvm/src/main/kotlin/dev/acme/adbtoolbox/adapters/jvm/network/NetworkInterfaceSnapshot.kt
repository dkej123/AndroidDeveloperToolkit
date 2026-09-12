package dev.acme.adbtoolbox.adapters.jvm.network

/** One raw `java.net.NetworkInterface` observation, JVM-only (never crosses into `:domain`). */
data class NetworkInterfaceSnapshot(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val ipv4Addresses: List<String>,
)
