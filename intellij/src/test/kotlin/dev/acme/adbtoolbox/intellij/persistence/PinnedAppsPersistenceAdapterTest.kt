package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.adb.DeviceSerial

class PinnedAppsPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test pinned apps round-trip and survive a selection write`() {
        val state = AdbToolboxProjectState()
        val pins = PinnedAppsPersistenceAdapter(state)

        pins.writeNow(setOf("com.acme.shop", "com.acme.wallet"))
        AppsSelectionPersistenceAdapter(state).writeSelectedPackageNow(SelectedPackage(DeviceSerial.of("emulator-5554"), "com.acme.shop"))

        assertEquals(setOf("com.acme.shop", "com.acme.wallet"), pins.readNow())
    }

    fun `test blank entries and an unknown schema degrade to nothing pinned`() {
        assertEquals(setOf("a"), resolvePinnedApps(PinnedAppsState().apply { packageNames = mutableListOf(" a ", "  ") }))
        assertEquals(emptySet<String>(), resolvePinnedApps(PinnedAppsState().apply { schemaVersion = 99; packageNames = mutableListOf("a") }))
    }
}
