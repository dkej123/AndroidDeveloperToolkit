package dev.acme.adbtoolbox.adapters.jvm.network

import dev.acme.adbtoolbox.domain.network.HostInterfaceEnumeration
import dev.acme.adbtoolbox.domain.network.NetworkInterfaceCandidate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JvmHostNetworkInfoTest {

    @Test
    fun `maps one candidate per interface-address pair from synthetic snapshots`() {
        val lister = NetworkInterfaceLister {
            listOf(
                NetworkInterfaceSnapshot(
                    name = "en0",
                    displayName = "Wi-Fi",
                    isUp = true,
                    isLoopback = false,
                    ipv4Addresses = listOf("10.0.4.117"),
                ),
                NetworkInterfaceSnapshot(
                    name = "lo0",
                    displayName = "Loopback",
                    isUp = true,
                    isLoopback = true,
                    ipv4Addresses = listOf("127.0.0.1"),
                ),
            )
        }

        val result = JvmHostNetworkInfo(lister).enumerateInterfaces()

        result shouldBe HostInterfaceEnumeration.Success(
            listOf(
                NetworkInterfaceCandidate("en0", "Wi-Fi", "10.0.4.117", isUp = true, isLoopback = false),
                NetworkInterfaceCandidate("lo0", "Loopback", "127.0.0.1", isUp = true, isLoopback = true),
            ),
        )
    }

    @Test
    fun `emits no candidate for an interface with no IPv4 addresses`() {
        val lister = NetworkInterfaceLister {
            listOf(
                NetworkInterfaceSnapshot(
                    name = "utun4",
                    displayName = "utun4",
                    isUp = true,
                    isLoopback = false,
                    ipv4Addresses = emptyList(),
                ),
            )
        }

        JvmHostNetworkInfo(lister).enumerateInterfaces() shouldBe HostInterfaceEnumeration.Success(emptyList())
    }

    @Test
    fun `emits one candidate per address when an interface has multiple IPv4 addresses`() {
        val lister = NetworkInterfaceLister {
            listOf(
                NetworkInterfaceSnapshot(
                    name = "eth0",
                    displayName = "eth0",
                    isUp = true,
                    isLoopback = false,
                    ipv4Addresses = listOf("192.168.1.20", "192.168.1.21"),
                ),
            )
        }

        JvmHostNetworkInfo(lister).enumerateInterfaces() shouldBe HostInterfaceEnumeration.Success(
            listOf(
                NetworkInterfaceCandidate("eth0", "eth0", "192.168.1.20", isUp = true, isLoopback = false),
                NetworkInterfaceCandidate("eth0", "eth0", "192.168.1.21", isUp = true, isLoopback = false),
            ),
        )
    }

    @Test
    fun `maps an enumeration failure to a Failed outcome instead of throwing`() {
        val lister = NetworkInterfaceLister { throw java.net.SocketException("permission denied") }

        JvmHostNetworkInfo(lister).enumerateInterfaces() shouldBe HostInterfaceEnumeration.Failed("permission denied")
    }

    @Test
    fun `falls back to the exception class name when the message is null`() {
        val lister = NetworkInterfaceLister { throw java.net.SocketException() }

        val result = JvmHostNetworkInfo(lister).enumerateInterfaces()

        result shouldBe HostInterfaceEnumeration.Failed("SocketException")
    }
}
