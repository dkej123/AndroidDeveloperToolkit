package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.apps.AppsRow
import java.awt.event.MouseEvent

private fun row(name: String, label: String = name, selected: Boolean = false, debuggable: Boolean? = null) =
    AppsRow(packageName = name, label = label, labelResolved = label != name, isDebuggable = debuggable, isSelected = selected)

class AppsVirtualListTest : BasePlatformTestCase() {

    fun `test clicking a row invokes onSelect with its exact package name`() {
        var selected: String? = null
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = { name -> selected = name })
        model.apply(listOf(row("com.acme.shop"), row("com.acme.wallet")))
        list.setBounds(0, 0, 200, 200)
        list.doLayout()

        val bounds = list.getCellBounds(1, 1)
        for (listener in list.mouseListeners) {
            listener.mouseClicked(
                MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, bounds.x + 1, bounds.y + 1, 1, false),
            )
        }

        assertEquals("com.acme.wallet", selected)
    }

    fun `test the cell renderer shows the label and package name in their own labels`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {})
        val entry = row("com.acme.shop", label = "Shop")
        model.apply(listOf(entry))

        list.cellRenderer.getListCellRendererComponent(list, entry, 0, false, false)
        val renderer = list.rowRendererForTest

        assertEquals("Shop", renderer.titleLabelForTest.text)
        assertEquals("com.acme.shop", renderer.packageLabelForTest.text)
    }

    fun `test only a debuggable row shows the debug tag, using the brand tile treatment`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {})
        val debuggable = row("com.acme.shop", debuggable = true)
        val notDebuggable = row("com.acme.other", debuggable = false)
        model.apply(listOf(debuggable, notDebuggable))
        val renderer = list.rowRendererForTest

        list.cellRenderer.getListCellRendererComponent(list, debuggable, 0, false, false)
        assertTrue(renderer.debugTagForTest.isVisible)
        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.brandBg, renderer.tileForTest.background)

        list.cellRenderer.getListCellRendererComponent(list, notDebuggable, 1, false, false)
        assertFalse(renderer.debugTagForTest.isVisible)
        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.header, renderer.tileForTest.background)
    }

    fun `test a selected row is bold and tinted with the accent background`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {})
        val selected = row("com.acme.shop", selected = true)
        model.apply(listOf(selected))
        val renderer = list.rowRendererForTest

        list.cellRenderer.getListCellRendererComponent(list, selected, 0, false, false)

        assertEquals(java.awt.Font.BOLD, renderer.titleLabelForTest.font.style)
        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.accentBg, renderer.rootForTest.background)
        assertTrue(renderer.rootForTest.isOpaque)
    }

    fun `test the selected row is reflected as the JList selection`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {})

        model.apply(listOf(row("com.acme.shop", selected = false), row("com.acme.wallet", selected = true)))
        list.syncSelectionFromRows()

        assertEquals(1, list.selectedIndex)
    }

    fun `test disposal removes this list's own click listener without throwing`() {
        var selected: String? = null
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = { name -> selected = name })
        model.apply(listOf(row("com.acme.shop")))
        list.setBounds(0, 0, 200, 200)
        list.doLayout()

        list.disposeList()

        val bounds = list.getCellBounds(0, 0)
        for (listener in list.mouseListeners) {
            listener.mouseClicked(
                MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, bounds.x + 1, bounds.y + 1, 1, false),
            )
        }
        assertNull(selected)
    }
}
