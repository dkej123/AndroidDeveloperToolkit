package dev.acme.adbtoolbox.domain.network

/**
 * One IPv4 address observed on one host network interface, as reported by [HostNetworkInfo].
 * Platform-neutral: the JVM adapter maps `java.net.NetworkInterface`/`InetAddress` into this shape
 * so [HostIpv4SelectionPolicy] never touches JVM networking types (ADR 0002).
 */
data class NetworkInterfaceCandidate(
    val interfaceName: String,
    val displayName: String,
    val ipv4Address: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
)
