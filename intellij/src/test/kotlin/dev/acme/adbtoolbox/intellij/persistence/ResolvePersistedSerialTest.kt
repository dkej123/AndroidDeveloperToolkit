package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * [resolvePersistedSerial] needs no IntelliJ Platform API — it is a pure function over a plain
 * bean — but is kept as a [BasePlatformTestCase] like every other test in this module:
 * [dev.acme.adbtoolbox.intellij.composition.AdbTransportSelectionTest]'s class doc documents that a
 * plain JUnit 5 `@Test` class here is not discovered by `:intellij:test`'s IntelliJ-Platform-aware
 * runner. Covers task 009's persistence graceful-degradation requirement: missing, corrupt, and
 * older/newer-schema persisted data must all resolve to `null` ("no selection") rather than throw.
 */
class ResolvePersistedSerialTest : BasePlatformTestCase() {

    fun `test a valid serial at the current schema version resolves`() {
        val state = DeviceSelectionState().apply { selectedDeviceSerial = "R58N90ABCDE" }

        assertEquals(DeviceSerial.of("R58N90ABCDE"), resolvePersistedSerial(state))
    }

    fun `test missing (default, never-persisted) state resolves to null`() {
        assertNull(resolvePersistedSerial(DeviceSelectionState()))
    }

    fun `test a blank serial string is treated as corrupt and resolves to null`() {
        val state = DeviceSelectionState().apply { selectedDeviceSerial = "" }

        assertNull(resolvePersistedSerial(state))
    }

    fun `test a blank-after-trim serial string is treated as corrupt and resolves to null`() {
        val state = DeviceSelectionState().apply { selectedDeviceSerial = "   " }

        assertNull(resolvePersistedSerial(state))
    }

    fun `test an older schema version resolves to null rather than trusting a shape this class may not match`() {
        val state = DeviceSelectionState().apply {
            schemaVersion = 0
            selectedDeviceSerial = "R58N90ABCDE"
        }

        assertNull(resolvePersistedSerial(state))
    }

    fun `test a newer, not-yet-understood schema version resolves to null`() {
        val state = DeviceSelectionState().apply {
            schemaVersion = DeviceSelectionState.CURRENT_SCHEMA_VERSION + 1
            selectedDeviceSerial = "R58N90ABCDE"
        }

        assertNull(resolvePersistedSerial(state))
    }
}
