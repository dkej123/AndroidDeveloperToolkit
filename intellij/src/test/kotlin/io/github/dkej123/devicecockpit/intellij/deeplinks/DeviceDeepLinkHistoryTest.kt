package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import io.github.dkej123.devicecockpit.application.deeplinks.RecentDeviceLink
import io.github.dkej123.devicecockpit.intellij.persistence.DeviceDeepLinkHistory

class DeviceDeepLinkHistoryTest : BasePlatformTestCase() {
    fun testHistorySurvivesXmlRoundTripAndIsBounded() {
        val store = DeviceDeepLinkHistory()
        val entries = (1..51).map { RecentDeviceLink("test://item/$it?x=O'Reilly&y=2", if (it % 2 == 0) "com.example" else "", it.toLong(), handledBy = if (it % 3 == 0) "com.handler" else "") }
        store.replace(entries)
        val serialized = XmlSerializer.serialize(store.state)
        val loaded = DeviceDeepLinkHistory().apply {
            loadState(XmlSerializer.deserialize(serialized, DeviceDeepLinkHistory.HistoryState::class.java))
        }
        assertEquals(entries.take(50), loaded.entries())
    }
}
