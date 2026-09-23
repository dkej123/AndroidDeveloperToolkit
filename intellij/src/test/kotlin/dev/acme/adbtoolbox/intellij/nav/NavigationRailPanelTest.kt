package dev.acme.adbtoolbox.intellij.nav

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.nav.NavigationBadge
import dev.acme.adbtoolbox.domain.nav.ViewId
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke

/**
 * [NavigationRailPanel] is the neutral navigation region (task 012). Kept as a
 * [BasePlatformTestCase] like every other test in this module (see
 * [dev.acme.adbtoolbox.intellij.composition.AdbTransportSelectionTest]'s class doc).
 */
class NavigationRailPanelTest : BasePlatformTestCase() {

    fun `test every destination is present in the rail, in ViewId declaration order`() {
        val panel = NavigationRailPanel()

        val items = (0 until panel.list.model.size).map { panel.list.model.getElementAt(it) }

        assertEquals(ViewId.entries, items)
    }

    fun `test clicking (selecting) a destination fires onSelect with that destination`() {
        val panel = NavigationRailPanel()
        var selected: ViewId? = null
        panel.onSelect = { selected = it }

        panel.list.selectedIndex = ViewId.entries.indexOf(ViewId.Logcat)

        assertEquals(ViewId.Logcat, selected)
    }

    fun `test setSelected updates the list selection without firing onSelect`() {
        val panel = NavigationRailPanel()
        var selectCount = 0
        panel.onSelect = { selectCount++ }

        panel.setSelected(ViewId.Network)

        assertEquals(ViewId.Network, panel.list.selectedValue)
        assertEquals(0, selectCount)
    }

    fun `test setSelected for the already-selected destination is a no-op`() {
        val panel = NavigationRailPanel()
        panel.setSelected(ViewId.Device)
        var selectCount = 0
        panel.onSelect = { selectCount++ }

        panel.setSelected(ViewId.Device)

        assertEquals(0, selectCount)
    }

    fun `test the list has a real Down-arrow key binding for keyboard traversal within the rail`() {
        val panel = NavigationRailPanel()
        panel.list.selectedIndex = 0
        val downKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)
        val actionKey = panel.list.getInputMap(JComponent.WHEN_FOCUSED).get(downKeyStroke)
        assertNotNull("expected a Down-arrow binding on the rail's list", actionKey)
        val action = panel.list.actionMap.get(actionKey)
        assertNotNull(action)

        action.actionPerformed(ActionEvent(panel.list, ActionEvent.ACTION_PERFORMED, null))

        assertEquals(1, panel.list.selectedIndex)
    }

    fun `test a keyboard-driven selection change fires onSelect with the newly selected destination`() {
        val panel = NavigationRailPanel()
        panel.list.selectedIndex = 0
        var selected: ViewId? = null
        panel.onSelect = { selected = it }
        val downKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)
        val action = panel.list.actionMap.get(panel.list.getInputMap(JComponent.WHEN_FOCUSED).get(downKeyStroke))

        action.actionPerformed(ActionEvent(panel.list, ActionEvent.ACTION_PERFORMED, null))

        assertEquals(ViewId.entries[1], selected)
    }

    fun `test the active destination's cell renders with an accent background`() {
        val panel = NavigationRailPanel()

        // The cell renderer is one shared, reused Swing component (the standard ListCellRenderer
        // pattern) — each result must be read before the next call re-styles the same instance.
        val activeBackground = (
            panel.list.cellRenderer.getListCellRendererComponent(panel.list, ViewId.Display, 2, true, false) as JLabel
            ).background
        val inactiveBackground = (
            panel.list.cellRenderer.getListCellRendererComponent(panel.list, ViewId.Network, 3, false, false) as JLabel
            ).background

        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.accentBg, activeBackground)
        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.panel, inactiveBackground)
    }

    fun `test Settings uses the platform gear icon instead of a supplied rail glyph`() {
        val panel = NavigationRailPanel()

        val settingsCell = panel.list.cellRenderer.getListCellRendererComponent(
            panel.list, ViewId.Settings, ViewId.entries.indexOf(ViewId.Settings), false, false,
        ) as JLabel

        assertEquals(com.intellij.icons.AllIcons.General.Settings, settingsCell.icon)
    }

    fun `test updateBadges does not throw and is readable by the cell renderer`() {
        val panel = NavigationRailPanel()

        panel.updateBadges(mapOf(ViewId.Logcat to NavigationBadge.Attention, ViewId.Display to NavigationBadge.Count(1)))

        // No crash on repaint with badges set, and re-rendering the cell for a badged destination succeeds.
        val cell = panel.list.cellRenderer.getListCellRendererComponent(
            panel.list, ViewId.Logcat, ViewId.entries.indexOf(ViewId.Logcat), false, false,
        )
        assertNotNull(cell)
    }

    fun `test each destination exposes a tooltip via mouse position`() {
        val panel = NavigationRailPanel()
        panel.size = java.awt.Dimension(34, 200)
        panel.list.setBounds(0, 0, 34, 200)
        panel.list.doLayout()

        val bounds = panel.list.getCellBounds(ViewId.entries.indexOf(ViewId.Device), ViewId.entries.indexOf(ViewId.Device))
        val tooltip = panel.list.getToolTipText(
            MouseEvent(panel.list, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, bounds.x + 1, bounds.y + 1, 0, false),
        )

        assertEquals("Device — mirroring, capture, facts", tooltip)
    }

    fun `test each destination's rendered cell exposes the same descriptive text as an accessible name`() {
        val panel = NavigationRailPanel()

        val cell = panel.list.cellRenderer.getListCellRendererComponent(
            panel.list, ViewId.Network, ViewId.entries.indexOf(ViewId.Network), false, false,
        ) as JLabel

        assertEquals("Network — global proxy", cell.getAccessibleContext().accessibleName)
        assertEquals("Network — global proxy", cell.toolTipText)
    }

    fun `test Settings is pinned to the rail bottom and only its button area is a hit target`() {
        val panel = NavigationRailPanel()
        panel.setBounds(0, 0, 34, 500)
        panel.doLayout()
        panel.list.doLayout()

        val settingsIndex = ViewId.entries.indexOf(ViewId.Settings)
        val bounds = panel.list.getCellBounds(settingsIndex, settingsIndex)
        val bottom = panel.list.height - panel.list.insets.bottom
        assertEquals("Settings row must reach the rail bottom", bottom, bounds.y + bounds.height)

        val railButton = dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Sizes.railButton
        assertEquals(settingsIndex, panel.list.locationToIndex(java.awt.Point(17, bottom - railButton / 2)))
        assertEquals(-1, panel.list.locationToIndex(java.awt.Point(17, bounds.y + 2)))
    }
}
