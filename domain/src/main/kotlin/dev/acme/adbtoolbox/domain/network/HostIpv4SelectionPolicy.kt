package dev.acme.adbtoolbox.domain.network

/**
 * Pure selection policy: picks the usable developer-machine LAN IPv4 out of a raw candidate list
 * (design/README.md §6 — "Use my computer IP"). Never touches JVM networking types itself; the
 * candidates it receives are already mapped by [HostNetworkInfo]'s adapter.
 *
 * Excludes, in order:
 * - down interfaces (`isUp == false`)
 * - loopback interfaces (`isLoopback == true`)
 * - known virtual/container/tunnel interface names (a documented, not exhaustive, judgment call —
 *   `docker*`, `veth*`/`vEthernet*`, `virbr*`, `br-*`, `utun*`/`tun*`/`tap*`, `vboxnet*`,
 *   `vmnet*`, `ppp*`, `awdl*`, `llw*`, `anpi*`, `bridge*`, `gif*`, `stf*` — covering the common
 *   Docker/Linux, Windows Hyper-V, and macOS VPN/AirDrop/tunnel adapter naming conventions)
 * - link-local addresses (`169.254.0.0/16`, RFC 3927 — IPv6 link-local is out of scope, IPv4 only)
 * - malformed/non-IPv4 address strings
 *
 * Remaining candidates are sorted by ([interfaceName], [ipv4Address]) for deterministic output
 * regardless of input order or the underlying platform's (unordered) enumeration order. Exactly one
 * remaining candidate resolves; more than one is reported as [HostIpv4Result.Ambiguous] rather than
 * guessed at, since nothing in the candidate shape reliably distinguishes a machine's "primary"
 * adapter from a second equally-valid one.
 */
object HostIpv4SelectionPolicy {

    private val virtualInterfaceNamePrefixes = listOf(
        "docker", "veth", "virbr", "br-", "utun", "tun", "tap", "vboxnet", "vmnet", "ppp",
        "awdl", "llw", "anpi", "bridge", "gif", "stf",
    )

    private val ipv4Pattern = Regex(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$",
    )

    fun select(candidates: List<NetworkInterfaceCandidate>): HostIpv4Result {
        val usable = candidates
            .filter(::isUsable)
            .sortedWith(compareBy({ it.interfaceName }, { it.ipv4Address }))

        return when (usable.size) {
            0 -> HostIpv4Result.NotFound
            1 -> HostIpv4Result.Resolved(usable.single())
            else -> HostIpv4Result.Ambiguous(usable)
        }
    }

    private fun isUsable(candidate: NetworkInterfaceCandidate): Boolean =
        candidate.isUp &&
            !candidate.isLoopback &&
            !isVirtualInterfaceName(candidate.interfaceName) &&
            isValidIpv4Address(candidate.ipv4Address) &&
            !isLinkLocalIpv4Address(candidate.ipv4Address)

    private fun isVirtualInterfaceName(name: String): Boolean {
        val normalized = name.lowercase()
        return virtualInterfaceNamePrefixes.any { normalized.startsWith(it) }
    }

    private fun isValidIpv4Address(address: String): Boolean = ipv4Pattern.matches(address)

    private fun isLinkLocalIpv4Address(address: String): Boolean = address.startsWith("169.254.")
}
