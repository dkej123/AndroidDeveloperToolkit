package dev.acme.adbtoolbox.adapters.jvm.mcp

import dev.acme.adbtoolbox.domain.capture.MarkBox
import dev.acme.adbtoolbox.domain.capture.ScreenMark
import dev.acme.adbtoolbox.domain.capture.ScreenMarker
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * [ScreenMarker] on `javax.imageio` (task 066). Detection: edges of the grey image (Sobel), grown by
 * a few pixels so the parts of one control join, then connected regions; tiny and screen-sized
 * regions are dropped. Works on MCP screenshots, where 1 px = 1 dp.
 */
class AwtScreenMarker : ScreenMarker {

    override fun detect(png: ByteArray): List<MarkBox> {
        val image = decode(png) ?: return emptyList()
        val w = image.width
        val h = image.height
        val grey = IntArray(w * h) { i ->
            val rgb = image.getRGB(i % w, i / w)
            ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000
        }
        val edge = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun g(dx: Int, dy: Int) = grey[(y + dy) * w + x + dx]
            val gx = g(1, -1) + 2 * g(1, 0) + g(1, 1) - g(-1, -1) - 2 * g(-1, 0) - g(-1, 1)
            val gy = g(-1, 1) + 2 * g(0, 1) + g(1, 1) - g(-1, -1) - 2 * g(0, -1) - g(1, -1)
            edge[y * w + x] = abs(gx) + abs(gy) > EDGE_THRESHOLD
        }
        val grown = dilate(edge, w, h)
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val boxes = mutableListOf<MarkBox>()
        for (start in grown.indices) {
            if (!grown[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            while (top > 0) {
                val p = stack[--top]
                val px = p % w
                val py = p / w
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0) visit(p - 1, grown, seen, stack, top).also { top = it }
                if (px < w - 1) visit(p + 1, grown, seen, stack, top).also { top = it }
                if (py > 0) visit(p - w, grown, seen, stack, top).also { top = it }
                if (py < h - 1) visit(p + w, grown, seen, stack, top).also { top = it }
            }
            // Undo the growth so the box hugs the shape.
            val left = (minX + GROW).coerceAtMost(maxX)
            val topY = (minY + GROW).coerceAtMost(maxY)
            val box = MarkBox(left, topY, (maxX - GROW).coerceAtLeast(left) - left + 1, (maxY - GROW).coerceAtLeast(topY) - topY + 1)
            if (box.width >= MIN_SIDE && box.height >= MIN_SIDE && box.area * 2 <= w.toLong() * h) boxes += box
        }
        return boxes.sortedWith(compareBy({ it.top }, { it.left }))
    }

    override fun draw(png: ByteArray, marks: List<ScreenMark>): ByteArray? {
        val source = decode(png) ?: return null
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.drawImage(source, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, LABEL_SIZE)
            g.stroke = BasicStroke(2f)
            marks.forEach { mark ->
                val color = if (mark.fromTree) TREE else PIXELS
                val b = mark.box
                g.color = color
                g.drawRect(b.left, b.top, b.width - 1, b.height - 1)
                val metrics = g.fontMetrics
                val labelW = metrics.stringWidth(mark.label) + 6
                val labelH = metrics.height
                val labelY = if (b.top >= labelH) b.top - labelH else b.top
                g.fillRect(b.left, labelY, labelW, labelH)
                g.color = Color.WHITE
                g.drawString(mark.label, b.left + 3, labelY + metrics.ascent)
            }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun visit(p: Int, grown: BooleanArray, seen: BooleanArray, stack: IntArray, top: Int): Int {
        if (!grown[p] || seen[p]) return top
        seen[p] = true
        stack[top] = p
        return top + 1
    }

    /** Square dilation by [GROW], as two separable passes. */
    private fun dilate(edge: BooleanArray, w: Int, h: Int): BooleanArray {
        val rows = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (edge[y * w + x]) for (d in maxOf(0, x - GROW)..minOf(w - 1, x + GROW)) rows[y * w + d] = true
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (rows[y * w + x]) for (d in maxOf(0, y - GROW)..minOf(h - 1, y + GROW)) out[d * w + x] = true
        }
        return out
    }

    private fun decode(png: ByteArray): BufferedImage? = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()

    private companion object {
        /** Sobel |gx| + |gy| on 0–255 grey; about a 10 % contrast step. */
        const val EDGE_THRESHOLD = 100
        /** dp — joins the glyphs of a label and an icon with its text. */
        const val GROW = 3
        /** dp — smaller regions are noise. */
        const val MIN_SIDE = 8
        const val LABEL_SIZE = 11
        val TREE = Color(0x00897B)
        val PIXELS = Color(0xEF6C00)
    }
}
