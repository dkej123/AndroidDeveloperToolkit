package io.github.dkej123.devicecockpit.adapters.jvm.mcp

import io.github.dkej123.devicecockpit.domain.capture.ImageScaler
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** [ImageScaler] on `javax.imageio`, bilinear — screenshots for MCP at 1 px = 1 dp. */
class AwtImageScaler : ImageScaler {
    override fun scale(png: ByteArray, width: Int, height: Int): ByteArray? {
        val source = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(target, "png", it) }.toByteArray()
    }
}
