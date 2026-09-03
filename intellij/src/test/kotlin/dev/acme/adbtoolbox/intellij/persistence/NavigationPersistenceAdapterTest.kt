package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.nav.ViewId

/**
 * Headless platform tests for task 012's persistence adapter — same `:intellij:test` sandbox as
 * [DeviceSelectionPersistenceAdapterTest]. Exercises [NavigationPersistenceAdapter.readLastViewNow]/
 * `writeLastViewNow` directly (plain, non-`suspend` functions) for the same reason
 * [DeviceSelectionPersistenceAdapterTest]'s class doc documents. The graceful-degradation *logic*
 * is covered without any IntelliJ fixture at all by [ResolvePersistedViewIdTest].
 */
class NavigationPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test a fresh project has no persisted last view`() {
        val adapter = NavigationPersistenceAdapter(AdbToolboxProjectState())

        assertNull(adapter.readLastViewNow())
    }

    fun `test a written view id round-trips back out`() {
        val adapter = NavigationPersistenceAdapter(AdbToolboxProjectState())

        adapter.writeLastViewNow(ViewId.Network)

        assertEquals(ViewId.Network, adapter.readLastViewNow())
    }

    fun `test writing a new view id overwrites the previously persisted one`() {
        val adapter = NavigationPersistenceAdapter(AdbToolboxProjectState())
        adapter.writeLastViewNow(ViewId.Apps)

        adapter.writeLastViewNow(ViewId.Settings)

        assertEquals(ViewId.Settings, adapter.readLastViewNow())
    }

    fun `test the device-selection and navigation slices do not interfere with each other`() {
        val projectState = AdbToolboxProjectState()
        val deviceAdapter = DeviceSelectionPersistenceAdapter(projectState)
        val navAdapter = NavigationPersistenceAdapter(projectState)

        navAdapter.writeLastViewNow(ViewId.Display)

        assertNull(deviceAdapter.readSelectedSerialNow())
        assertEquals(ViewId.Display, navAdapter.readLastViewNow())
    }

    fun `test loadState composes via XmlSerializerUtil so a re-loaded view id survives`() {
        val projectState = AdbToolboxProjectState()
        val adapter = NavigationPersistenceAdapter(projectState)
        adapter.writeLastViewNow(ViewId.Logcat)

        val reloaded = AdbToolboxProjectState()
        reloaded.loadState(projectState.state)

        assertEquals(ViewId.Logcat, NavigationPersistenceAdapter(reloaded).readLastViewNow())
    }
}
