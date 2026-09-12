package dev.acme.adbtoolbox.domain.network

/** Result of one [HostNetworkInfo.enumerateInterfaces] call — separates "the JVM could not list
 * interfaces at all" from "it listed them, and some/none/one are usable" (the latter is decided by
 * [HostIpv4SelectionPolicy], not the port). */
sealed interface HostInterfaceEnumeration {
    data class Success(val candidates: List<NetworkInterfaceCandidate>) : HostInterfaceEnumeration

    data class Failed(val reason: String) : HostInterfaceEnumeration
}
