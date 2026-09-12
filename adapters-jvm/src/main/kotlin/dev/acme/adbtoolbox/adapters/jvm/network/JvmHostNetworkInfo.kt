package dev.acme.adbtoolbox.adapters.jvm.network

import dev.acme.adbtoolbox.domain.network.HostInterfaceEnumeration
import dev.acme.adbtoolbox.domain.network.HostNetworkInfo
import dev.acme.adbtoolbox.domain.network.NetworkInterfaceCandidate

/** Maps [NetworkInterfaceLister] snapshots to `:domain`'s [NetworkInterfaceCandidate] shape — one
 * candidate per (interface, IPv4 address) pair, so an interface with zero IPv4 addresses
 * contributes no candidate. The only place this plugin reads JVM networking APIs (ADR 0002); the
 * default [lister] is [SystemNetworkInterfaceLister], injectable for tests. */
class JvmHostNetworkInfo(
    private val lister: NetworkInterfaceLister = SystemNetworkInterfaceLister(),
) : HostNetworkInfo {

    override fun enumerateInterfaces(): HostInterfaceEnumeration = try {
        val candidates = lister.list().flatMap { snapshot ->
            snapshot.ipv4Addresses.map { address ->
                NetworkInterfaceCandidate(
                    interfaceName = snapshot.name,
                    displayName = snapshot.displayName,
                    ipv4Address = address,
                    isUp = snapshot.isUp,
                    isLoopback = snapshot.isLoopback,
                )
            }
        }
        HostInterfaceEnumeration.Success(candidates)
    } catch (exception: Exception) {
        HostInterfaceEnumeration.Failed(exception.message ?: exception::class.simpleName ?: "unknown error")
    }
}
