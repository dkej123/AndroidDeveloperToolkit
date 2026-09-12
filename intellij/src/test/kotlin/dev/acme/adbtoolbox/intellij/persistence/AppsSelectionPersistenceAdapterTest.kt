package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackage

/**
 * Headless platform tests for task 022's persistence adapter — same `:intellij:test` sandbox as
 * [DeviceSelectionPersistenceAdapterTest]. Exercises [AppsSelectionPersistenceAdapter.readSelectedPackageNow]/
 * `writeSelectedPackageNow` directly (plain, non-`suspend` functions) for the same documented reason
 * as [DeviceSelectionPersistenceAdapterTest].
 */
class AppsSelectionPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test a fresh project has no persisted selection`() {
        val adapter = AppsSelectionPersistenceAdapter(AdbToolboxProjectState())

        assertNull(adapter.readSelectedPackageNow())
    }

    fun `test a written selection round-trips back out`() {
        val adapter = AppsSelectionPersistenceAdapter(AdbToolboxProjectState())
        val selection = SelectedPackage(DeviceSerial.of("R58N90ABCDE"), "com.acme.shop")

        adapter.writeSelectedPackageNow(selection)

        assertEquals(selection, adapter.readSelectedPackageNow())
    }

    fun `test writing null clears the persisted selection`() {
        val adapter = AppsSelectionPersistenceAdapter(AdbToolboxProjectState())
        adapter.writeSelectedPackageNow(SelectedPackage(DeviceSerial.of("R58N90ABCDE"), "com.acme.shop"))

        adapter.writeSelectedPackageNow(null)

        assertNull(adapter.readSelectedPackageNow())
    }

    fun `test loadState composes via XmlSerializerUtil so a re-loaded selection survives`() {
        val projectState = AdbToolboxProjectState()
        val adapter = AppsSelectionPersistenceAdapter(projectState)
        val selection = SelectedPackage(DeviceSerial.of("R58N90ABCDE"), "com.acme.shop")
        adapter.writeSelectedPackageNow(selection)

        val reloaded = AdbToolboxProjectState()
        reloaded.loadState(projectState.state)

        assertEquals(selection, AppsSelectionPersistenceAdapter(reloaded).readSelectedPackageNow())
    }

    fun `test this adapter never touches the device-selection or settings slices`() {
        val projectState = AdbToolboxProjectState()
        val deviceAdapter = DeviceSelectionPersistenceAdapter(projectState)
        deviceAdapter.writeSelectedSerialNow(DeviceSerial.of("OTHERSERIAL"))

        AppsSelectionPersistenceAdapter(projectState).writeSelectedPackageNow(
            SelectedPackage(DeviceSerial.of("R58N90ABCDE"), "com.acme.shop"),
        )

        assertEquals(DeviceSerial.of("OTHERSERIAL"), deviceAdapter.readSelectedSerialNow())
    }
}
