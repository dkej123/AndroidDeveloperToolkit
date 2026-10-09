package io.github.dkej123.devicecockpit.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.apps.AppsRow
import io.github.dkej123.devicecockpit.domain.packages.AppIcon
import java.awt.Color
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private fun row(name: String, label: String = name, selected: Boolean = false, debuggable: Boolean? = null) =
    AppsRow(packageName = name, label = label, labelResolved = label != name, isDebuggable = debuggable, isSelected = selected)

private fun pngIcon(color: Color): AppIcon {
    val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().apply { this.color = color; fillRect(0, 0, 32, 32); dispose() }
    return AppIcon(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
}

class AppsVirtualListTest : BasePlatformTestCase() {

    private fun click(list: AppsVirtualList, x: Int, y: Int) {
        for (listener in list.mouseListeners) {
            listener.mouseClicked(MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, x, y, 1, false))
        }
    }

    fun `test the list shrinks with its scroll pane when the tool window gets narrower`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {})
        model.apply(listOf(row("com.acme.shop"), row("com.acme.wallet")))
        val scrollPane = javax.swing.JScrollPane(list)
        scrollPane.setSize(400, 300)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        // Rows re-measured at the wide width (e.g. an icon or label update arrives).
        model.apply(listOf(row("com.acme.shop", label = "Shop"), row("com.acme.wallet")))
        scrollPane.viewport.doLayout()

        scrollPane.setSize(200, 300)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()

        assertEquals(scrollPane.viewport.width, list.width)
        assertEquals(list.width - list.insets.left - list.insets.right, list.getCellBounds(0, 0).width)
    }

    fun `test clicking a row's pin toggles the pin instead of selecting the row`() {
        var pinned: String? = null
        var selected: String? = null
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = { selected = it }, onTogglePin = { pinned = it })
        model.apply(listOf(row("com.acme.shop"), row("com.acme.wallet")))
        list.setBounds(0, 0, 300, 200)
        list.doLayout()

        val bounds = list.getCellBounds(1, 1)
        click(list, bounds.x + bounds.width - com.intellij.util.ui.JBUI.scale(14), bounds.y + bounds.height / 2)

        assertEquals("com.acme.wallet", pinned)
        assertNull(selected)
    }

    fun `test a row with a section header is taller and shows the header above the row`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {}).apply { setSize(320, 400) }
        val first = row("com.acme.shop").copy(isPinned = true, sectionHeader = "Pinned")
        val plain = row("com.acme.wallet")
        model.apply(listOf(first, plain))
        list.doLayout()
        val renderer = list.rowRendererForTest

        list.cellRenderer.getListCellRendererComponent(list, first, 0, false, false)
        assertTrue(renderer.sectionHeaderForTest.isVisible)
        assertEquals("PINNED", renderer.sectionHeaderForTest.text)
        assertSame(io.github.dkej123.devicecockpit.intellij.icons.AdbToolboxIcons.Actions.pinned, renderer.pinForTest.icon)
        assertTrue(list.getCellBounds(0, 0).height > list.getCellBounds(1, 1).height)

        list.cellRenderer.getListCellRendererComponent(list, plain, 1, false, false)
        assertFalse(renderer.sectionHeaderForTest.isVisible)
        assertSame(io.github.dkej123.devicecockpit.intellij.icons.AdbToolboxIcons.Actions.pin, renderer.pinForTest.icon)
    }

    fun `test a row with a launcher icon paints that icon in place of the colored tile`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {}).apply { setSize(320, 200) }
        val withIcon = row("com.acme.shop", debuggable = true).copy(icon = pngIcon(Color.RED))
        val withoutIcon = row("com.acme.other", debuggable = true)
        model.apply(listOf(withIcon, withoutIcon))
        val renderer = list.rowRendererForTest

        list.cellRenderer.getListCellRendererComponent(list, withIcon, 0, false, false)
        assertNotNull(renderer.tileForTest.icon)
        assertNull(renderer.tileForTest.fill)
        val painted = BufferedImage(renderer.tileForTest.width, renderer.tileForTest.height, BufferedImage.TYPE_INT_ARGB)
        renderer.tileForTest.paint(painted.createGraphics())
        assertEquals(Color.RED.rgb, painted.getRGB(painted.width / 2, painted.height / 2))

        list.cellRenderer.getListCellRendererComponent(list, withoutIcon, 1, false, false)
        assertNull(renderer.tileForTest.icon)
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Colors.brandBg, renderer.tileForTest.fill)
    }

    fun `test an undecodable icon falls back to the colored tile`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {})
        val broken = row("com.acme.shop", debuggable = false).copy(icon = AppIcon(byteArrayOf(1, 2, 3)))
        model.apply(listOf(broken))
        val renderer = list.rowRendererForTest

        list.cellRenderer.getListCellRendererComponent(list, broken, 0, false, false)

        assertNull(renderer.tileForTest.icon)
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Colors.header, renderer.tileForTest.fill)
    }

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

    fun `test nested row labels receive paintable bounds from the renderer`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {}).apply { setSize(320, 200) }
        val entry = row("com.acme.shop", label = "Shop", debuggable = true)
        model.apply(listOf(entry))

        list.cellRenderer.getListCellRendererComponent(list, entry, 0, true, false)
        val renderer = list.rowRendererForTest

        assertTrue(renderer.titleLabelForTest.width > 0)
        assertTrue(renderer.packageLabelForTest.width > 0)
        assertTrue(renderer.tileForTest.width > 0)
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
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Colors.brandBg, renderer.tileForTest.fill)

        list.cellRenderer.getListCellRendererComponent(list, notDebuggable, 1, false, false)
        assertFalse(renderer.debugTagForTest.isVisible)
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Colors.header, renderer.tileForTest.fill)
    }

    fun `test a selected row is bold and tinted with the accent background`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {})
        val selected = row("com.acme.shop", selected = true)
        model.apply(listOf(selected))
        val renderer = list.rowRendererForTest

        list.cellRenderer.getListCellRendererComponent(list, selected, 0, false, false)

        assertEquals(java.awt.Font.BOLD, renderer.titleLabelForTest.font.style)
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Colors.accentBg, renderer.rootForTest.fill)
        assertEquals(io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Colors.accentBorder, renderer.rootForTest.outline)
    }

    fun `test the app tile is a 16px square that is not stretched to the row height`() {
        val model = AppsVirtualListModel { true }
        val list = AppsVirtualList(model, onSelect = {}).apply { setSize(346, 200) }
        val value = row("com.acme.shop", debuggable = true)
        model.apply(listOf(value))
        val renderer = list.rowRendererForTest

        list.cellRenderer.getListCellRendererComponent(list, value, 0, false, false)

        val tile = renderer.tileForTest
        assertEquals(com.intellij.util.ui.JBUI.scale(16), tile.width)
        assertEquals(com.intellij.util.ui.JBUI.scale(16), tile.height)
        assertTrue(renderer.debugTagForTest.height < io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme.Sizes.appRow / 2)
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
