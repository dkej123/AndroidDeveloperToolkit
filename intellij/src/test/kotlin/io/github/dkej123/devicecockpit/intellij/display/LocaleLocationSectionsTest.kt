package io.github.dkej123.devicecockpit.intellij.display

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.locale.DeviceLocaleState
import io.github.dkej123.devicecockpit.application.locale.LocaleRow
import io.github.dkej123.devicecockpit.application.locale.LocaleViewState
import io.github.dkej123.devicecockpit.application.locale.LocationViewState
import io.github.dkej123.devicecockpit.domain.location.GeoPoint
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import java.awt.event.MouseEvent

class LocaleLocationSectionsTest : BasePlatformTestCase() {

    private fun click(component: java.awt.Component) {
        component.mouseListeners.forEach { it.mouseClicked(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false)) }
    }

    fun `test the meta shows the locale and turns amber with the original note when overridden`() {
        val section = LocaleSection({}, {}, {})
        section.update(LocaleViewState(locale = DeviceLocaleState("en-US", "en-US"), loading = false), deviceOnline = true)
        assertEquals("en-US", section.metaLabelForTest.text)
        assertFalse(section.overrideRowForTest.isVisible)

        section.update(LocaleViewState(locale = DeviceLocaleState("ar-XB", "en-US"), loading = false), deviceOnline = true)

        assertEquals("ar-XB applied", section.metaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.amber, section.metaLabelForTest.foreground)
        assertTrue(section.overrideRowForTest.isVisible)
    }

    fun `test a row applies its locale, the active one does not`() {
        val applied = mutableListOf<String>()
        val section = LocaleSection({}, { applied += it }, {})
        section.update(LocaleViewState(locale = DeviceLocaleState("ar-XB", "en-US"), loading = false), deviceOnline = true)

        val rows = section.rowsForTest.filterIsInstance<LocaleSection.LocaleRowView>()
        assertEquals(listOf("en-XA", "ar-XB", "ar-EG", "he-IL"), rows.map { it.row.tag })
        click(rows.first { it.row.tag == "en-XA" })
        click(rows.first { it.row.tag == "ar-XB" })

        assertEquals(listOf("en-XA"), applied)
    }

    fun `test search results and the empty message`() {
        val queries = mutableListOf<String>()
        val section = LocaleSection({ queries += it }, {}, {})
        section.searchFieldForTest.text = "xx"
        assertEquals("xx", queries.last())

        section.update(LocaleViewState(locale = DeviceLocaleState("en-US", "en-US"), loading = false, query = "xx", rows = emptyList()), deviceOnline = true)

        assertTrue(section.emptyLabelForTest.isVisible)
        section.update(LocaleViewState(loading = false, query = "pol", rows = listOf(LocaleRow("pl-PL", "Polski"))), deviceOnline = true)
        assertFalse(section.emptyLabelForTest.isVisible)
    }

    fun `test location presets, custom coordinates and the physical-device state`() {
        val presets = mutableListOf<String>()
        val customs = mutableListOf<Pair<String, String>>()
        val section = LocationSection({ presets += it }, { lat, lon -> customs += lat to lon })
        section.update(LocationViewState(emulator = true), deviceOnline = true)
        assertEquals("not set", section.metaLabelForTest.text)

        section.chipRowForTest.chips.first { it.text == "Warsaw" }.doClick()
        assertEquals(listOf("Warsaw"), presets)
        val custom = section.chipRowForTest.chips.last()
        assertEquals("…", custom.text)
        assertEquals("Custom location", custom.accessibleContext.accessibleName)
        custom.doClick()
        assertTrue(section.customRowForTest.isVisible)
        section.typeCustomForTest("52.1", "21.0")
        assertEquals(listOf("52.1" to "21.0"), customs)

        section.update(LocationViewState(emulator = true, current = "Warsaw" to GeoPoint.of(52.2297, 21.0122)!!, latitudeError = "Latitude must be −90 to 90"), deviceOnline = true)
        assertEquals("Warsaw · 52.2297, 21.0122", section.metaLabelForTest.text)
        assertEquals("Latitude must be −90 to 90", section.errorLabelForTest.text)

        section.update(LocationViewState(emulator = false), deviceOnline = true)
        assertEquals("emulator only", section.metaLabelForTest.text)
        assertFalse(section.chipRowForTest.chips.first().isEnabled)
    }
}
