package io.github.dkej123.devicecockpit.intellij.visual

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.intellij.devicebar.DeviceContextBarPanel
import io.github.dkej123.devicecockpit.intellij.feedback.FeedbackStatusPanel
import io.github.dkej123.devicecockpit.intellij.nav.NavigationRailPanel
import io.github.dkej123.devicecockpit.intellij.display.DisplayPanel
import io.github.dkej123.devicecockpit.intellij.network.NetworkPanel
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

/** Exact global regions from `design/README.md`'s layout diagram, exercised in their real parent layout. */
class GlobalChromeLayoutTest : BasePlatformTestCase() {

    fun `test global chrome keeps the supplied fixed dimensions at dock width`() {
        val deviceBar = DeviceContextBarPanel({}, {})
        val rail = NavigationRailPanel()
        val status = FeedbackStatusPanel()
        val root = JPanel(BorderLayout()).apply {
            add(deviceBar, BorderLayout.NORTH)
            add(rail, BorderLayout.WEST)
            add(JPanel(), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
            setSize(AdbToolboxTheme.Breakpoints.defaultDock, 620)
            doLayout()
        }

        assertEquals(AdbToolboxTheme.Sizes.deviceBar, deviceBar.height)
        assertEquals(AdbToolboxTheme.Sizes.rail, rail.width)
        assertEquals(AdbToolboxTheme.Sizes.statusBar, status.height)
    }

    fun `test device bar and status bar content is vertically centered in its fixed row`() {
        val deviceBar = DeviceContextBarPanel({}, {}).apply {
            update(io.github.dkej123.devicecockpit.application.devicebar.DeviceBarPresentation.NoDevice)
            setSize(AdbToolboxTheme.Breakpoints.defaultDock, AdbToolboxTheme.Sizes.deviceBar)
        }
        val status = FeedbackStatusPanel().apply {
            update(io.github.dkej123.devicecockpit.domain.feedback.StatusState(message = "Ready"))
            updateOverrideCount(2)
            setSize(AdbToolboxTheme.Breakpoints.defaultDock, AdbToolboxTheme.Sizes.statusBar)
        }
        listOf(deviceBar, status).forEach(::layoutTree)

        listOf(
            deviceBar to deviceBar.refreshButtonForTest,
            status to status.overrideChipComponentForTest,
        ).forEach { (bar, control) ->
            val bounds = javax.swing.SwingUtilities.convertRectangle(control.parent, control.bounds, bar)
            val barCenter = (bar.height - bar.insets.bottom + bar.insets.top) / 2.0
            assertEquals("${control.javaClass.simpleName} is not vertically centered: $bounds", barCenter, bounds.centerY, 1.5)
        }
    }

    fun `test the status bar keeps breathing room above and below its override chip`() {
        val status = FeedbackStatusPanel().apply {
            update(io.github.dkej123.devicecockpit.domain.feedback.StatusState(message = "Ready"))
            updateOverrideCount(2)
            setSize(AdbToolboxTheme.Breakpoints.defaultDock, AdbToolboxTheme.Sizes.statusBar)
        }
        layoutTree(status)

        val chip = status.overrideChipComponentForTest
        val bounds = javax.swing.SwingUtilities.convertRectangle(chip.parent, chip.bounds, status)
        assertEquals(com.intellij.util.ui.JBUI.scale(28), status.height)
        assertTrue("chip too close to the top: $bounds", bounds.y >= com.intellij.util.ui.JBUI.scale(5))
        assertTrue("chip too close to the bottom: $bounds", status.height - bounds.maxY >= com.intellij.util.ui.JBUI.scale(4))
    }

    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }

    fun `test form views track the viewport width and never expose a horizontal scrollbar`() {
        val panels = listOf(
            DisplayPanel({}, {}, {}, {}, {}, {}, {}, {}),
            NetworkPanel({}, {}, {}, {}, {}, {}),
        )

        panels.forEach { panel ->
            val scroll = panel.descendants().filterIsInstance<JScrollPane>().single()
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)
            val view = scroll.viewport.view as Scrollable
            assertTrue(view.scrollableTracksViewportWidth)
        }
    }

    private fun Component.descendants(): List<Component> = buildList {
        add(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { addAll(it.descendants()) }
        }
    }
}
