package dev.acme.adbtoolbox.adapters.jvm.network

import dev.acme.adbtoolbox.domain.network.HostInterfaceEnumeration
import dev.acme.adbtoolbox.domain.network.resolveHostIpv4
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * A real-environment check: resolves this machine's actual LAN IPv4 via [SystemNetworkInterfaceLister]
 * and [JvmHostNetworkInfo], asserting only that enumeration completes without throwing. Disabled by
 * default — normal `test`/`koverVerify` runs use synthetic interfaces only (`JvmHostNetworkInfoTest`,
 * `HostIpv4SelectionPolicyTest`), since real interfaces vary unpredictably across dev machines and CI.
 *
 * Enable explicitly with: `./gradlew :adapters-jvm:test -DadbToolbox.hostIpSmokeTest=true`
 */
@EnabledIfSystemProperty(named = "adbToolbox.hostIpSmokeTest", matches = "true")
class JvmHostNetworkInfoSmokeTest {

    @Test
    fun `resolves this machine's real network state without throwing`() {
        val hostNetworkInfo = JvmHostNetworkInfo()

        when (val enumeration = hostNetworkInfo.enumerateInterfaces()) {
            is HostInterfaceEnumeration.Success ->
                println("enumerated ${enumeration.candidates.size} candidate(s)")

            is HostInterfaceEnumeration.Failed ->
                println("enumeration failed (expected in some sandboxes): ${enumeration.reason}")
        }

        println("resolveHostIpv4() -> ${hostNetworkInfo.resolveHostIpv4()}")
    }
}
