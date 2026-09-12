package dev.acme.adbtoolbox.domain.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HostIpv4DiscoveryTest {

    @Test
    fun `resolves through the selection policy when enumeration succeeds`() {
        val candidate = NetworkInterfaceCandidate("en0", "Wi-Fi", "10.0.4.117", isUp = true, isLoopback = false)
        val info = FakeHostNetworkInfo(HostInterfaceEnumeration.Success(listOf(candidate)))

        info.resolveHostIpv4() shouldBe HostIpv4Result.Resolved(candidate)
    }

    @Test
    fun `surfaces an enumeration failure as DiscoveryFailed`() {
        val info = FakeHostNetworkInfo(HostInterfaceEnumeration.Failed("permission denied"))

        info.resolveHostIpv4() shouldBe HostIpv4Result.DiscoveryFailed("permission denied")
    }
}
