package dev.acme.adbtoolbox.adapters.jvm.network

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Reads the real, live network interfaces via `java.net.NetworkInterface`/`InetAddress` — no
 * shell command (`ifconfig`/`ipconfig`/`ip addr`) is ever invoked, per
 * design/IMPLEMENTATION.md §4. Works unchanged on macOS, Linux, and Windows: the JVM presents all
 * three through the same API, and the OS-specific virtual-interface-name judgment call lives in
 * `:domain`'s `HostIpv4SelectionPolicy`, not here. */
class SystemNetworkInterfaceLister : NetworkInterfaceLister {
    override fun list(): List<NetworkInterfaceSnapshot> =
        Collections.list(NetworkInterface.getNetworkInterfaces()).map { networkInterface ->
            NetworkInterfaceSnapshot(
                name = networkInterface.name,
                displayName = networkInterface.displayName ?: networkInterface.name,
                isUp = networkInterface.isUp,
                isLoopback = networkInterface.isLoopback,
                ipv4Addresses = Collections.list(networkInterface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress },
            )
        }
}
