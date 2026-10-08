package dev.acme.adbtoolbox.intellij.icons

import junit.framework.TestCase
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Icon

class TintedIconTest : TestCase() {

    /** 4×4: left half opaque red, right half 50% green, nothing painted below row 2. */
    private val glyph = object : Icon {
        override fun getIconWidth() = 4
        override fun getIconHeight() = 4
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            g.color = Color.RED
            g.fillRect(x, y, 2, 2)
            g.color = Color(0, 255, 0, 128)
            g.fillRect(x + 2, y, 2, 2)
        }
    }

    fun `test tint replaces the color and keeps each pixel's alpha`() {
        val canvas = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)

        AdbToolboxIcons.tinted(glyph, Color.BLUE).paintIcon(null, canvas.createGraphics(), 0, 0)

        assertEquals(0xFF0000FF.toInt(), canvas.getRGB(0, 0))
        val half = Color(canvas.getRGB(3, 0), true)
        assertEquals(0, half.red)
        assertEquals(0, half.green)
        assertTrue("alpha ${half.alpha}", half.alpha in 120..136)
        assertEquals(0, canvas.getRGB(0, 3) ushr 24)
    }

    fun `test tint keeps the source size and paints at device scale`() {
        val tinted = AdbToolboxIcons.tinted(glyph, Color.BLUE)
        val canvas = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)

        tinted.paintIcon(null, canvas.createGraphics().apply { scale(2.0, 2.0) }, 0, 0)

        assertEquals(4, tinted.iconWidth)
        assertEquals(0xFF0000FF.toInt(), canvas.getRGB(3, 3))
        assertEquals(0, canvas.getRGB(3, 4) ushr 24)
    }
}
