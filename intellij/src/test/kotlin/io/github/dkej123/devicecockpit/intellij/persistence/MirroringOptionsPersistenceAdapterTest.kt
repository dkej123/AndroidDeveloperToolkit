package io.github.dkej123.devicecockpit.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions

/**
 * Headless platform tests for task 040's persistence adapter — same `:intellij:test` sandbox as
 * [SettingsPersistenceAdapterTest] (see its class doc for why `...Now` functions are exercised
 * directly instead of the port's `suspend` methods, and why this stays a [BasePlatformTestCase]).
 */
class MirroringOptionsPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test a fresh project has default mirroring options`() {
        val adapter = MirroringOptionsPersistenceAdapter(DeviceCockpitProjectState())

        assertEquals(MirroringOptions.DEFAULT, adapter.readOptionsNow())
    }

    fun `test written options round-trip back out, including a null maxSize and bit rate`() {
        val adapter = MirroringOptionsPersistenceAdapter(DeviceCockpitProjectState())
        val options = MirroringOptions(stayAwake = true, showTouches = true, maxSize = 1920, videoBitRateMbps = 8, turnScreenOff = true)

        adapter.writeOptionsNow(options)

        assertEquals(options, adapter.readOptionsNow())
    }

    fun `test writing default options clears a previously written maxSize and bit rate`() {
        val adapter = MirroringOptionsPersistenceAdapter(DeviceCockpitProjectState())
        adapter.writeOptionsNow(MirroringOptions(maxSize = 1920, videoBitRateMbps = 8))

        adapter.writeOptionsNow(MirroringOptions.DEFAULT)

        assertEquals(MirroringOptions.DEFAULT, adapter.readOptionsNow())
    }

    fun `test loadState composes via XmlSerializerUtil so written options survive a reload`() {
        val projectState = DeviceCockpitProjectState()
        val adapter = MirroringOptionsPersistenceAdapter(projectState)
        val options = MirroringOptions(stayAwake = true, maxSize = 1280)
        adapter.writeOptionsNow(options)

        val reloaded = DeviceCockpitProjectState()
        reloaded.loadState(projectState.state)

        assertEquals(options, MirroringOptionsPersistenceAdapter(reloaded).readOptionsNow())
    }
}
