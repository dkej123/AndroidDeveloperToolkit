package dev.acme.adbtoolbox.domain.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun endpoint(host: String, port: Int) = ProxyEndpoint(
    (ProxyHost.parse(host) as ProxyHostResult.Valid).host,
    (ProxyPort.parse(port) as ProxyPortResult.Valid).port,
)

/** [RecentProxyEndpoints]' pure MRU ordering/dedup/bound policy (task 032). */
class RecentProxyEndpointsTest {

    @Test
    fun `a fresh list starts empty`() {
        RecentProxyEndpoints().endpoints shouldBe emptyList()
    }

    @Test
    fun `the first added endpoint becomes the sole entry`() {
        val recents = RecentProxyEndpoints().withMostRecent(endpoint("10.0.4.117", 8888))

        recents.endpoints shouldBe listOf(endpoint("10.0.4.117", 8888))
    }

    @Test
    fun `a newer endpoint is inserted at the front`() {
        val recents = RecentProxyEndpoints()
            .withMostRecent(endpoint("10.0.4.117", 8888))
            .withMostRecent(endpoint("proxy.acme.dev", 3128))

        recents.endpoints shouldBe listOf(endpoint("proxy.acme.dev", 3128), endpoint("10.0.4.117", 8888))
    }

    @Test
    fun `re-adding an existing endpoint moves it to the front without duplicating it`() {
        val recents = RecentProxyEndpoints()
            .withMostRecent(endpoint("10.0.4.117", 8888))
            .withMostRecent(endpoint("proxy.acme.dev", 3128))
            .withMostRecent(endpoint("10.0.4.117", 8888))

        recents.endpoints shouldBe listOf(endpoint("10.0.4.117", 8888), endpoint("proxy.acme.dev", 3128))
    }

    @Test
    fun `the list never grows past MAX_RECENTS, dropping the oldest entry`() {
        var recents = RecentProxyEndpoints()
        for (port in 1..(RecentProxyEndpoints.MAX_RECENTS + 2)) {
            recents = recents.withMostRecent(endpoint("10.0.4.117", port))
        }

        recents.endpoints.size shouldBe RecentProxyEndpoints.MAX_RECENTS
        recents.endpoints.first() shouldBe endpoint("10.0.4.117", RecentProxyEndpoints.MAX_RECENTS + 2)
        recents.endpoints.none { it.port.value == 1 } shouldBe true
        recents.endpoints.none { it.port.value == 2 } shouldBe true
    }
}
