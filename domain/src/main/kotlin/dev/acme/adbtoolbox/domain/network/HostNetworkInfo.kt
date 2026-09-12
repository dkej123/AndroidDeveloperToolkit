package dev.acme.adbtoolbox.domain.network

/**
 * Reports the host machine's network interfaces (ADR 0001) — implemented by `:adapters-jvm`'s
 * `JvmHostNetworkInfo`, wrapping `java.net.NetworkInterface`/`InetAddress` without shelling out to
 * `ifconfig`/`ipconfig`/`ip addr` (design/IMPLEMENTATION.md §4). The only place this plugin reads
 * JVM networking APIs — always behind this port so `:domain` stays free of them (ADR 0002).
 */
interface HostNetworkInfo {
    fun enumerateInterfaces(): HostInterfaceEnumeration
}
