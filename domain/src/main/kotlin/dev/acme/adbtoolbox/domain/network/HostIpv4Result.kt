package dev.acme.adbtoolbox.domain.network

/** Outcome of resolving the developer machine's usable LAN IPv4 address — always actionable, never
 * a bare failure a UI cannot render meaningfully. */
sealed interface HostIpv4Result {

    /** Exactly one usable candidate was found. */
    data class Resolved(val candidate: NetworkInterfaceCandidate) : HostIpv4Result

    /** More than one equally-valid candidate was found; [candidates] is in the same deterministic
     * order [HostIpv4SelectionPolicy] would apply to any repeat call with the same input. */
    data class Ambiguous(val candidates: List<NetworkInterfaceCandidate>) : HostIpv4Result

    /** No usable candidate was found after excluding loopback, link-local, down, and virtual
     * interfaces. */
    data object NotFound : HostIpv4Result

    /** Enumerating the host's network interfaces failed (e.g. a `SocketException` from the JVM). */
    data class DiscoveryFailed(val reason: String) : HostIpv4Result
}
