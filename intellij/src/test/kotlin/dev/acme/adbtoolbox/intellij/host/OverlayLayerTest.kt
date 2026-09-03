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
}
