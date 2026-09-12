package dev.acme.adbtoolbox.domain.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HostIpv4SelectionPolicyTest {

    private fun candidate(
        interfaceName: String = "en0",
        displayName: String = "Wi-Fi",
        ipv4Address: String = "10.0.4.117",
        isUp: Boolean = true,
        isLoopback: Boolean = false,
    ) = NetworkInterfaceCandidate(interfaceName, displayName, ipv4Address, isUp, isLoopback)

    @Test
    fun `selects the single valid candidate`() {
        val valid = candidate()

        HostIpv4SelectionPolicy.select(listOf(valid)) shouldBe HostIpv4Result.Resolved(valid)
    }

    @Test
    fun `excludes a loopback candidate`() {
        val loopback = candidate(interfaceName = "lo0", ipv4Address = "127.0.0.1", isLoopback = true)

        HostIpv4SelectionPolicy.select(listOf(loopback)) shouldBe HostIpv4Result.NotFound
    }

    @Test
    fun `excludes a link-local candidate`() {
        val linkLocal = candidate(ipv4Address = "169.254.1.5")

        HostIpv4SelectionPolicy.select(listOf(linkLocal)) shouldBe HostIpv4Result.NotFound
    }

    @Test
    fun `excludes a down interface`() {
        val down = candidate(isUp = false)

        HostIpv4SelectionPolicy.select(listOf(down)) shouldBe HostIpv4Result.NotFound
    }

    @Test
    fun `excludes known virtual interface names`() {
        val virtualCandidates = listOf(
            candidate(interfaceName = "docker0", ipv4Address = "172.17.0.1"),
            candidate(interfaceName = "veth3f2a1b", ipv4Address = "172.17.0.2"),
            candidate(interfaceName = "utun4", ipv4Address = "10.10.10.1"),
            candidate(interfaceName = "vEthernet (Default Switch)", ipv4Address = "172.20.0.1"),
            candidate(interfaceName = "br-9f1c2a", ipv4Address = "172.18.0.1"),
        )

        virtualCandidates.forEach { virtual ->
            HostIpv4SelectionPolicy.select(listOf(virtual)) shouldBe HostIpv4Result.NotFound
        }
    }

    @Test
    fun `reports multiple valid candidates as ambiguous in deterministic order`() {
        val eth0 = candidate(interfaceName = "eth0", ipv4Address = "192.168.1.20")
        val wlan0 = candidate(interfaceName = "wlan0", ipv4Address = "192.168.1.21")

        val inOneOrder = HostIpv4SelectionPolicy.select(listOf(wlan0, eth0))
        val inReverseOrder = HostIpv4SelectionPolicy.select(listOf(eth0, wlan0))

        inOneOrder shouldBe inReverseOrder
        inOneOrder shouldBe HostIpv4Result.Ambiguous(listOf(eth0, wlan0))
    }

    @Test
    fun `returns not found when no candidates are given`() {
        HostIpv4SelectionPolicy.select(emptyList()) shouldBe HostIpv4Result.NotFound
    }

    @Test
    fun `excludes a candidate with a malformed IPv4 address`() {
        val malformed = candidate(ipv4Address = "not-an-address")

        HostIpv4SelectionPolicy.select(listOf(malformed)) shouldBe HostIpv4Result.NotFound
    }
}
