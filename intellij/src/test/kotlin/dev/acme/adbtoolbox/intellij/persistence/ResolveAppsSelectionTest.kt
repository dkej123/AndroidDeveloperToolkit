package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackage

/**
 * [resolveAppsSelection] needs no IntelliJ Platform API — it is a pure function over a plain bean —
 * but is kept as a [BasePlatformTestCase] like every other test in this module (see
 * [ResolvePersistedSerialTest]'s class doc for why). Covers task 022's graceful-degradation
 * requirement: missing, corrupt (blank package name), and older/newer-schema persisted data must
 * all resolve to "no selection" rather than throw or fabricate a selection.
 */
class ResolveAppsSelectionTest : BasePlatformTestCase() {

    fun `test missing (default, never-persisted) state resolves to no selection`() {
        assertNull(resolveAppsSelection(AppsSelectionState()))
    }

    fun `test a fully populated current-schema state resolves to the exact serial and package name`() {
        val state = AppsSelectionState().apply {
            selectedDeviceSerial = "R58N90ABCDE"
            selectedPackageName = "com.acme.shop"
        }

        assertEquals(SelectedPackage(DeviceSerial.of("R58N90ABCDE"), "com.acme.shop"), resolveAppsSelection(state))
    }

    fun `test a blank package name is treated as corrupt and resolves to no selection`() {
        val state = AppsSelectionState().apply {
            selectedDeviceSerial = "R58N90ABCDE"
            selectedPackageName = "   "
        }

        assertNull(resolveAppsSelection(state))
    }

    fun `test a blank serial is treated as corrupt and resolves to no selection`() {
        val state = AppsSelectionState().apply {
            selectedDeviceSerial = "   "
            selectedPackageName = "com.acme.shop"
        }

        assertNull(resolveAppsSelection(state))
    }

    fun `test a missing package name with a present serial resolves to no selection`() {
        val state = AppsSelectionState().apply { selectedDeviceSerial = "R58N90ABCDE" }

        assertNull(resolveAppsSelection(state))
    }

    fun `test a newer, not-yet-understood schema version resolves to no selection`() {
        val state = AppsSelectionState().apply {
            schemaVersion = AppsSelectionState.CURRENT_SCHEMA_VERSION + 1
            selectedDeviceSerial = "R58N90ABCDE"
            selectedPackageName = "com.acme.shop"
        }

        assertNull(resolveAppsSelection(state))
    }
}
