package io.github.dkej123.devicecockpit.intellij.inspector

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.TimeZone

class InspectorOpenRowTest : BasePlatformTestCase() {
    private val utc = TimeZone.getTimeZone("UTC")

    fun `test hidden while no inspector tab is open`() {
        val row = InspectorOpenRow(onShow = {}, onRecapture = {}, timeZone = utc)

        row.render(null)

        assertFalse(row.isVisible)
    }

    fun `test names the capture time and offers Show and Re-capture`() {
        var shown = 0
        var recaptured = 0
        val row = InspectorOpenRow(onShow = { shown++ }, onRecapture = { recaptured++ }, timeZone = utc)

        row.render(InspectorOpenStatus(capturedAtMillis = 1_791_380_671_000))
        row.showLinkForTest.doClick()
        row.recaptureLinkForTest.doClick()

        assertTrue(row.isVisible)
        assertEquals("Inspector open · captured 13:44:31", row.noteForTest)
        assertEquals(1, shown)
        assertEquals(1, recaptured)
    }

    fun `test says capturing until the first capture lands`() {
        val row = InspectorOpenRow(onShow = {}, onRecapture = {}, timeZone = utc)

        row.render(InspectorOpenStatus(capturedAtMillis = null))

        assertEquals("Inspector open · capturing…", row.noteForTest)
    }
}
