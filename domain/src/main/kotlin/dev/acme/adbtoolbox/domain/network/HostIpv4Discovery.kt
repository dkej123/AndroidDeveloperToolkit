package dev.acme.adbtoolbox.domain.network

/** Resolves this host's usable LAN IPv4 by enumerating interfaces through [HostNetworkInfo] and
 * applying [HostIpv4SelectionPolicy], surfacing an enumeration failure as
 * [HostIpv4Result.DiscoveryFailed] rather than throwing. */
fun HostNetworkInfo.resolveHostIpv4(): HostIpv4Result =
    when (val enumeration = enumerateInterfaces()) {
        is HostInterfaceEnumeration.Failed -> HostIpv4Result.DiscoveryFailed(enumeration.reason)
        is HostInterfaceEnumeration.Success -> HostIpv4SelectionPolicy.select(enumeration.candidates)
    }
