package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatPersistedControls

/**
 * Headless platform tests for task 037's persistence adapter — same `:intellij:test` sandbox as
 * [NetworkPersistenceAdapterTest]. Exercises [LogcatControlsPersistenceAdapter.readNow]/`writeNow`
 * directly (plain, non-`suspend` functions) for the same documented reason.
 */
class LogcatControlsPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test a fresh project has the default controls`() {
        val adapter = LogcatControlsPersistenceAdapter(AdbToolboxProjectState())

        assertEquals(LogcatPersistedControls(), adapter.readNow())
    }

    fun `test written controls round-trip back out`() {
        val adapter = LogcatControlsPersistenceAdapter(AdbToolboxProjectState())
        val controls = LogcatPersistedControls(minSeverity = LogSeverity.WARN, packageFilterOn = false, wrap = true)

        adapter.writeNow(controls)

        assertEquals(controls, adapter.readNow())
    }

    fun `test a null minimum severity round-trips as no floor`() {
        val adapter = LogcatControlsPersistenceAdapter(AdbToolboxProjectState())
        adapter.writeNow(LogcatPersistedControls(minSeverity = LogSeverity.ERROR))

        adapter.writeNow(LogcatPersistedControls(minSeverity = null))

        assertEquals(LogcatPersistedControls(minSeverity = null), adapter.readNow())
    }

    fun `test loadState composes via XmlSerializerUtil so reloaded controls survive`() {
        val projectState = AdbToolboxProjectState()
        val adapter = LogcatControlsPersistenceAdapter(projectState)
        val controls = LogcatPersistedControls(minSeverity = LogSeverity.INFO, packageFilterOn = false, wrap = true)
        adapter.writeNow(controls)

        val reloaded = AdbToolboxProjectState()
        reloaded.loadState(projectState.state)

        assertEquals(controls, LogcatControlsPersistenceAdapter(reloaded).readNow())
    }

    fun `test an unrecognized severity name degrades to no floor rather than failing the whole read`() {
        val projectState = AdbToolboxProjectState()
        projectState.state.logcatControls = LogcatControlsPersistenceState().apply {
            minSeverityName = "NOT_A_REAL_LEVEL"
            packageFilterOn = false
            wrap = true
        }

        val controls = LogcatControlsPersistenceAdapter(projectState).readNow()

        assertEquals(LogcatPersistedControls(minSeverity = null, packageFilterOn = false, wrap = true), controls)
    }

    fun `test an older schema version degrades to defaults rather than misinterpreting stale data`() {
        val projectState = AdbToolboxProjectState()
        projectState.state.logcatControls = LogcatControlsPersistenceState().apply {
            schemaVersion = LogcatControlsPersistenceState.CURRENT_SCHEMA_VERSION + 1
            minSeverityName = "ERROR"
        }

        assertEquals(LogcatPersistedControls(), LogcatControlsPersistenceAdapter(projectState).readNow())
    }

    fun `test this adapter never touches the network slice`() {
        val projectState = AdbToolboxProjectState()
        val networkAdapter = NetworkPersistenceAdapter(projectState)
        networkAdapter.writeRecentsNow(
            listOf(
                dev.acme.adbtoolbox.domain.network.ProxyEndpoint(
                    (dev.acme.adbtoolbox.domain.network.ProxyHost.parse("10.0.4.117")
                        as dev.acme.adbtoolbox.domain.network.ProxyHostResult.Valid).host,
                    (dev.acme.adbtoolbox.domain.network.ProxyPort.parse(8888)
                        as dev.acme.adbtoolbox.domain.network.ProxyPortResult.Valid).port,
                ),
            ),
        )

        LogcatControlsPersistenceAdapter(projectState).writeNow(LogcatPersistedControls(wrap = true))

        assertEquals(1, networkAdapter.readRecentsNow().size)
    }
}
