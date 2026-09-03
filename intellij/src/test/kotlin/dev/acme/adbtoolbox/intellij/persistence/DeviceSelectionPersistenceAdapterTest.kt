package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * Headless platform tests for task 009's persistence adapter — same `:intellij:test` sandbox as
 * [dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectServiceTest]/[dev.acme.adbtoolbox.intellij.composition.AdbTransportSelectionTest].
 * Exercises [DeviceSelectionPersistenceAdapter.readSelectedSerialNow]/`writeSelectedSerialNow`
 * directly (plain, non-`suspend` functions) rather than the port's `suspend` methods — this
 * sandbox's older bundled coroutines runtime cannot resolve `suspend` calls compiled by this
 * project's pinned Kotlin compiler (task 007's documented gap, `AdbTransportSelectionTest`'s class
 * doc); the `suspend` wrappers are trivial one-line delegations to these, so no coverage is lost.
 * The graceful-degradation *logic* (corrupt/older-schema data) is covered without any IntelliJ
 * fixture at all by [ResolvePersistedSerialTest], a plain JUnit test on the pure function.
 */
class DeviceSelectionPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test a fresh project has no persisted selection`() {
        val adapter = DeviceSelectionPersistenceAdapter(AdbToolboxProjectState())

        assertNull(adapter.readSelectedSerialNow())
    }

    fun `test a written serial round-trips back out`() {
        val adapter = DeviceSelectionPersistenceAdapter(AdbToolboxProjectState())
        val serial = DeviceSerial.of("R58N90ABCDE")

        adapter.writeSelectedSerialNow(serial)

        assertEquals(serial, adapter.readSelectedSerialNow())
    }

    fun `test writing null clears the persisted selection`() {
        val adapter = DeviceSelectionPersistenceAdapter(AdbToolboxProjectState())
        adapter.writeSelectedSerialNow(DeviceSerial.of("R58N90ABCDE"))

        adapter.writeSelectedSerialNow(null)

        assertNull(adapter.readSelectedSerialNow())
    }

    fun `test AdbToolboxProjectState is a project-scoped singleton`() {
        val first = project.service<AdbToolboxProjectState>()
        val second = project.service<AdbToolboxProjectState>()

        assertSame(first, second)
    }

    fun `test loadState composes via XmlSerializerUtil so a re-loaded serial survives`() {
        val projectState = AdbToolboxProjectState()
        val adapter = DeviceSelectionPersistenceAdapter(projectState)
        adapter.writeSelectedSerialNow(DeviceSerial.of("R58N90ABCDE"))

        val reloaded = AdbToolboxProjectState()
        reloaded.loadState(projectState.state)

        assertEquals(
            DeviceSerial.of("R58N90ABCDE"),
            DeviceSelectionPersistenceAdapter(reloaded).readSelectedSerialNow(),
        )
    }
}
