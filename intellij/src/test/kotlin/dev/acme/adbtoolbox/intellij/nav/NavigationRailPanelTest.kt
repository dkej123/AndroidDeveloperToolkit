package dev.acme.adbtoolbox.intellij.nav

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.nav.ViewId
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
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
}
