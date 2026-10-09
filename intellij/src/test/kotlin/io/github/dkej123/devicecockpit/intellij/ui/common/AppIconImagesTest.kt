package io.github.dkej123.devicecockpit.intellij.ui.common

import io.github.dkej123.devicecockpit.domain.packages.AppIcon
import junit.framework.TestCase
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class AppIconImagesTest : TestCase() {

    fun `test an undecodable icon paints nothing and reports it`() {
        val canvas = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)

        assertNull(AppIconImages.decode(AppIcon(byteArrayOf(1, 2, 3))))
        assertFalse(AppIconImages.paint(canvas.createGraphics(), AppIcon(byteArrayOf(1, 2, 3)), 0, 0, 16, 16))
    }

    fun `test a 64px icon is downscaled into a 16px tile without aliasing`() {
        // A one-pixel checkerboard averages to grey; a single unfiltered draw picks pure black or white.
        val icon = png(64) { x, y -> if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE }
        val canvas = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)

        assertTrue(AppIconImages.paint(canvas.createGraphics(), icon, 0, 0, 16, 16))

        val red = Color(canvas.getRGB(8, 8)).red
        assertTrue("expected grey, was $red", red in 64..192)
    }

    fun `test a 2x graphics paints from device pixels, not a stretched logical-size copy`() {
        // Left half black, right half white: at 2x the 28pt tile is 56 device px and the edge stays sharp.
        val icon = png(64) { x, _ -> if (x < 32) Color.BLACK else Color.WHITE }
        val canvas = BufferedImage(56, 56, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics().apply { scale(2.0, 2.0) }

        assertTrue(AppIconImages.paint(g, icon, 0, 0, 28, 28))

        assertEquals(Color.BLACK.rgb, canvas.getRGB(26, 28))
        assertEquals(Color.WHITE.rgb, canvas.getRGB(29, 28))
    }

    fun `test the label icon has the requested size`() {
        val icon = AppIconImages.icon(png(64) { _, _ -> Color.RED }, 28)!!

        assertEquals(com.intellij.util.ui.JBUI.scale(28), icon.iconWidth)
        assertNull(AppIconImages.icon(AppIcon(byteArrayOf(0)), 28))
    }

    private fun png(size: Int, color: (Int, Int) -> Color): AppIcon {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until size) for (y in 0 until size) image.setRGB(x, y, color(x, y).rgb)
        return AppIcon(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
    }
}
