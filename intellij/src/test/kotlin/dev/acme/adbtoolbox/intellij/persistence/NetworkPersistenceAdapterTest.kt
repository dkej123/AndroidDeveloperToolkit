package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult

private fun endpoint(host: String, port: Int) = ProxyEndpoint(
    (ProxyHost.parse(host) as ProxyHostResult.Valid).host,
    (ProxyPort.parse(port) as ProxyPortResult.Valid).port,
)

/**
 * Headless platform tests for task 032's persistence adapter — same `:intellij:test` sandbox as
 * [AppsSelectionPersistenceAdapterTest]. Exercises [NetworkPersistenceAdapter.readRecentsNow]/
 * `writeRecentsNow` directly (plain, non-`suspend` functions) for the same documented reason.
 */
class NetworkPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test a fresh project has no persisted recents`() {
        val adapter = NetworkPersistenceAdapter(AdbToolboxProjectState())

        assertEquals(emptyList<ProxyEndpoint>(), adapter.readRecentsNow())
    }

    fun `test written recents round-trip back out, most-recent first`() {
        val adapter = NetworkPersistenceAdapter(AdbToolboxProjectState())
        val recents = listOf(endpoint("proxy.acme.dev", 3128), endpoint("10.0.4.117", 8888))

        adapter.writeRecentsNow(recents)

        assertEquals(recents, adapter.readRecentsNow())
    }

    fun `test writing an empty list clears the persisted recents`() {
        val adapter = NetworkPersistenceAdapter(AdbToolboxProjectState())
        adapter.writeRecentsNow(listOf(endpoint("10.0.4.117", 8888)))

        adapter.writeRecentsNow(emptyList())

        assertEquals(emptyList<ProxyEndpoint>(), adapter.readRecentsNow())
    }

    fun `test loadState composes via XmlSerializerUtil so reloaded recents survive`() {
        val projectState = AdbToolboxProjectState()
        val adapter = NetworkPersistenceAdapter(projectState)
        val recents = listOf(endpoint("10.0.4.117", 8888))
        adapter.writeRecentsNow(recents)

        val reloaded = AdbToolboxProjectState()
        reloaded.loadState(projectState.state)

        assertEquals(recents, NetworkPersistenceAdapter(reloaded).readRecentsNow())
    }

    fun `test a corrupt individual entry is dropped rather than failing the whole list`() {
        val projectState = AdbToolboxProjectState()
        projectState.state.network = NetworkState().apply {
            recentEndpoints = mutableListOf("10.0.4.117:8888", "not-a-valid-endpoint", "proxy.acme.dev:3128")
        }

        val recents = NetworkPersistenceAdapter(projectState).readRecentsNow()

        assertEquals(listOf(endpoint("10.0.4.117", 8888), endpoint("proxy.acme.dev", 3128)), recents)
    }

    fun `test an older schema version degrades to an empty list rather than misinterpreting stale data`() {
        val projectState = AdbToolboxProjectState()
        projectState.state.network = NetworkState().apply {
            schemaVersion = NetworkState.CURRENT_SCHEMA_VERSION + 1
            recentEndpoints = mutableListOf("10.0.4.117:8888")
        }

        assertEquals(emptyList<ProxyEndpoint>(), NetworkPersistenceAdapter(projectState).readRecentsNow())
    }

    fun `test this adapter never touches the device-selection or apps slices`() {
        val projectState = AdbToolboxProjectState()
        val deviceAdapter = DeviceSelectionPersistenceAdapter(projectState)
        deviceAdapter.writeSelectedSerialNow(dev.acme.adbtoolbox.domain.adb.DeviceSerial.of("OTHERSERIAL"))

        NetworkPersistenceAdapter(projectState).writeRecentsNow(listOf(endpoint("10.0.4.117", 8888)))

        assertEquals(dev.acme.adbtoolbox.domain.adb.DeviceSerial.of("OTHERSERIAL"), deviceAdapter.readSelectedSerialNow())
    }
}
