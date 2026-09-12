package dev.acme.adbtoolbox.domain.network

/** A deterministic [HostNetworkInfo] test double: reports [enumeration] as-is, defaulting to an
 * empty successful enumeration. */
class FakeHostNetworkInfo(
    var enumeration: HostInterfaceEnumeration = HostInterfaceEnumeration.Success(emptyList()),
) : HostNetworkInfo {
    override fun enumerateInterfaces(): HostInterfaceEnumeration = enumeration
}
