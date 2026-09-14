package dev.acme.adbtoolbox.intellij.host

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLabel
import javax.swing.JLayeredPane

/**
 * [OverlayLayer] is the bounded "overlays" named slot (task 010). Kept as a [BasePlatformTestCase]
 * for the same reason as every other test in this module (see `AdbTransportSelectionTest`).
 */
class OverlayLayerTest : BasePlatformTestCase() {

    fun `test an overlay can be shown and is mounted onto the target layered pane`() {
        val target = JLayeredPane()
        val layer = OverlayLayer(target)
        val overlay = JLabel("confirm")

        layer.show(overlay)

        assertTrue(layer.isShowing(overlay))
        assertSame(target, overlay.parent)
    }

    fun `test dismissing a shown overlay removes it from the target`() {
        val target = JLayeredPane()
        val layer = OverlayLayer(target)
        val overlay = JLabel("confirm")
        layer.show(overlay)

        layer.dismiss(overlay)

        assertFalse(layer.isShowing(overlay))
        assertNull(overlay.parent)
    }

    fun `test an overlay never explicitly dismissed is still torn down by dismissAll`() {
        val target = JLayeredPane()
        val layer = OverlayLayer(target)
        val forgotten = JLabel("orphaned toast")
        layer.show(forgotten)

        layer.dismissAll()

        assertFalse(layer.isShowing(forgotten))
        assertNull(forgotten.parent)
        assertEquals(0, layer.overlayCount)
    }

    fun `test showing the same overlay twice does not duplicate it`() {
        val target = JLayeredPane()
        val layer = OverlayLayer(target)
        val overlay = JLabel("confirm")

        layer.show(overlay)
        layer.show(overlay)

        assertEquals(1, layer.overlayCount)
    }

    fun `test an overlay with layoutBounds is positioned against the target's current size`() {
        val target = JLayeredPane()
        target.setBounds(0, 0, 400, 300)
        val layer = OverlayLayer(target)
        val overlay = JLabel("picker")

        layer.show(overlay) { w, h -> java.awt.Rectangle(8, 62, w - 16, h - 62) }

        assertEquals(java.awt.Rectangle(8, 62, 384, 238), overlay.bounds)
    }

    fun `test relayout reapplies layoutBounds after the target is resized`() {
        val target = JLayeredPane()
        target.setBounds(0, 0, 400, 300)
        val layer = OverlayLayer(target)
        val overlay = JLabel("toast")
        layer.show(overlay) { w, h -> java.awt.Rectangle(8, h - 30, w - 16, 22) }

        target.setBounds(0, 0, 200, 500)
        layer.relayout()

        assertEquals(java.awt.Rectangle(8, 470, 184, 22), overlay.bounds)
    }

    fun `test showing again with a new layoutBounds updates the bounds without duplicating`() {
        val target = JLayeredPane()
        target.setBounds(0, 0, 400, 300)
        val layer = OverlayLayer(target)
        val overlay = JLabel("picker")
        layer.show(overlay) { _, _ -> java.awt.Rectangle(0, 0, 10, 10) }

        layer.show(overlay) { _, _ -> java.awt.Rectangle(5, 5, 20, 20) }

        assertEquals(java.awt.Rectangle(5, 5, 20, 20), overlay.bounds)
        assertEquals(1, layer.overlayCount)
    }
}
