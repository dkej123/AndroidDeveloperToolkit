package io.github.dkej123.devicecockpit.intellij.ui.common

import com.intellij.ui.scale.JBUIScale
import io.github.dkej123.devicecockpit.domain.packages.AppIcon
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_CACHED_IMAGES = 512

/**
 * Decodes each [AppIcon]'s PNG once and paints it sharp on HiDPI screens. The device renders icons
 * larger than any tile (ADR 0010), so a tile is drawn from a copy pre-scaled to its size in
 * *device* pixels — a single bicubic draw from the 64px source into a 16pt tile aliases, and an
 * image sized in logical pixels is stretched (blurry) on a 2x screen. Rows are re-rendered on every
 * repaint, so both the decoded and the scaled images are cached. EDT only.
 */
object AppIconImages {
    private val decoded = lruMap<AppIcon, BufferedImage?>()
    private val scaled = lruMap<Pair<AppIcon, Int>, BufferedImage>()

    /** The decoded icon, or `null` when its PNG cannot be read (the caller shows its fallback tile). */
    fun decode(icon: AppIcon): BufferedImage? {
        if (icon in decoded) return decoded[icon]
        return runCatching { ImageIO.read(ByteArrayInputStream(icon.png)) }.getOrNull().also { decoded[icon] = it }
    }

    /** Paints [icon] into `(x, y, width, height)` of [g]; `false` when it cannot be decoded. */
    fun paint(g: Graphics, icon: AppIcon, x: Int, y: Int, width: Int, height: Int): Boolean {
        val source = decode(icon) ?: return false
        val g2 = g.create() as Graphics2D
        try {
            val devicePx = max(1, (width * g2.transform.scaleX).roundToInt())
            val image = scaled.getOrPut(icon to devicePx) { downscale(source, devicePx) }
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.drawImage(image, x, y, width, height, null)
        } finally {
            g2.dispose()
        }
        return true
    }

    /** A [size]pt (JBUI-scaled) [Icon] for labels, painted through [paint]. */
    fun icon(icon: AppIcon, size: Int): Icon? = decode(icon)?.let {
        object : Icon {
            override fun getIconWidth(): Int = JBUIScale.scale(size)
            override fun getIconHeight(): Int = JBUIScale.scale(size)
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                paint(g, icon, x, y, iconWidth, iconHeight)
            }
        }
    }

    /** Halves with bilinear filtering until close, then one last bilinear step — no aliasing. */
    private fun downscale(source: BufferedImage, target: Int): BufferedImage {
        var current = source
        while (current.width / 2 >= target) current = resize(current, current.width / 2)
        return if (current.width == target) current else resize(current, target)
    }

    private fun resize(source: BufferedImage, size: Int): BufferedImage {
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return result
    }

    private fun <K, V> lruMap(): MutableMap<K, V> = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > MAX_CACHED_IMAGES
    }
}
