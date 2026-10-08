package dev.acme.adbtoolbox.intellij.inspector

import com.intellij.ui.scale.JBUIScale
import dev.acme.adbtoolbox.domain.layout.AccessibilityReport
import dev.acme.adbtoolbox.domain.layout.LayoutMeasurement
import dev.acme.adbtoolbox.domain.layout.PixelRect
import dev.acme.adbtoolbox.domain.layout.UiHierarchy
import dev.acme.adbtoolbox.domain.layout.UiNode
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a loaded design overlay is scaled (design §9 overlay bar). */
enum class OverlayScale(val label: String, val factor: Double?) { X1("1×", 1.0), X2("2×", 2.0), X3("3×", 3.0), FitWidth("Fit width", null) }

/** A design PNG laid over the capture: scaled, faded, optionally Difference-blended, nudged in dp. */
data class DesignOverlay(
    val name: String,
    val image: BufferedImage,
    val scale: OverlayScale,
    val opacity: Int = 50,
    val difference: Boolean = false,
    val nudgeX: Int = 0,
    val nudgeY: Int = 0,
)

/**
 * The inspector canvas (design §9): the frozen screenshot with the hierarchy on top — hover and
 * selection outlines with dp sizes, redlines between the selection and the hovered element, the
 * 8 dp grid, the color picker loupe, the design overlay and the accessibility badges. Geometry comes
 * from the capture in device pixels; [zoom] is canvas pixels per dp.
 */
class InspectorCanvas(
    private val onHover: (UiNode?) -> Unit,
    private val onSelect: (UiNode?) -> Unit,
    private val onCursor: (Double?, Double?) -> Unit,
    private val onPick: (Color) -> Unit,
    private val onZoomChanged: () -> Unit,
) : JComponent() {
    var screenshot: BufferedImage? = null
        set(value) {
            field = value
            secure = value?.let(::isBlack) ?: false
            differenceCache = null
            revalidateCanvas()
        }
    var hierarchy: UiHierarchy? = null
        set(value) {
            field = value
            revalidateCanvas()
        }
    var audit: AccessibilityReport? = null
    var auditMode = false
        set(value) { field = value; repaint() }
    var grid = false
        set(value) { field = value; repaint() }
    var picker = false
        set(value) {
            field = value
            cursor = Cursor.getPredefinedCursor(if (value) Cursor.CROSSHAIR_CURSOR else Cursor.DEFAULT_CURSOR)
            repaint()
        }
    var overlay: DesignOverlay? = null
        set(value) {
            field = value
            differenceCache = null
            repaint()
        }
    var selected: UiNode? = null
        set(value) { field = value; repaint() }
    var hovered: UiNode? = null
        set(value) { field = value; repaint() }

    /** A black screenshot: the app marks the screen FLAG_SECURE; the hierarchy is drawn as wireframes. */
    var secure = false
        private set

    /** Canvas pixels per dp; null = fit the viewport. */
    var zoom: Double? = null
        set(value) {
            field = value
            revalidateCanvas()
            onZoomChanged()
        }
    var fitSize: Dimension = Dimension(0, 0)
        set(value) {
            field = value
            if (zoom == null) revalidateCanvas()
        }

    private var mouse: Point? = null
    private var differenceCache: Pair<String, BufferedImage>? = null

    init {
        isOpaque = true
        isFocusable = true
        val mouseHandler = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = move(e.point)

            override fun mouseDragged(e: MouseEvent) = move(e.point)

            override fun mouseExited(e: MouseEvent) {
                mouse = null
                hovered = null
                onHover(null)
                onCursor(null, null)
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                requestFocusInWindow()
                if (picker) {
                    sample(e.point)?.let(onPick)
                    return
                }
                val node = nodeAt(e.point)
                selected = node
                onSelect(node)
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                if (e.isControlDown || e.isMetaDown) {
                    zoomBy(if (e.wheelRotation < 0) 1 else -1)
                } else {
                    parent?.dispatchEvent(e)
                }
            }
        }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)
    }

    // ---- geometry ----

    private val padding get() = JBUIScale.scale(24)

    /** The effective zoom (canvas px per dp). */
    val effectiveZoom: Double
        get() {
            zoom?.let { return it }
            val shot = screenshot ?: return 1.0
            val h = hierarchy ?: return 1.0
            return LayoutMeasurement.fitZoom(shot.width, shot.height, fitSize.width - 2 * padding, fitSize.height - 2 * padding, h.densityDpi)
        }

    /** Canvas pixels per device pixel. */
    private val scale: Double get() = effectiveZoom * 160.0 / (hierarchy?.densityDpi ?: 160)

    private fun screenRect(): Rectangle? {
        val shot = screenshot ?: return null
        return Rectangle(padding, padding, (shot.width * scale).roundToInt(), (shot.height * scale).roundToInt())
    }

    private fun toCanvas(r: PixelRect): Rectangle =
        Rectangle(padding + (r.left * scale).roundToInt(), padding + (r.top * scale).roundToInt(), (r.width * scale).roundToInt(), (r.height * scale).roundToInt())

    private fun toDevice(p: Point): Point = Point(((p.x - padding) / scale).toInt(), ((p.y - padding) / scale).toInt())

    override fun getPreferredSize(): Dimension {
        val rect = screenRect() ?: return Dimension(fitSize.width, fitSize.height)
        return Dimension(rect.width + 2 * padding, rect.height + 2 * padding)
    }

    private fun revalidateCanvas() {
        revalidate()
        repaint()
    }

    fun zoomBy(steps: Int) {
        val levels = listOf(0.5, 0.75, 1.0, 1.5, 2.0)
        val current = effectiveZoom
        zoom = if (steps > 0) levels.firstOrNull { it > current + 1e-6 } ?: levels.last() else levels.lastOrNull { it < current - 1e-6 } ?: levels.first()
    }

    fun nodeAt(p: Point): UiNode? {
        val h = hierarchy ?: return null
        val d = toDevice(p)
        return h.hitTest(d.x, d.y)
    }

    private fun move(p: Point) {
        mouse = p
        val h = hierarchy
        if (h != null && screenRect()?.contains(p) == true) {
            val d = toDevice(p)
            onCursor(h.dp(d.x), h.dp(d.y))
            if (!picker) {
                val node = h.hitTest(d.x, d.y)
                if (node != hovered) {
                    hovered = node
                    onHover(node)
                }
            }
        } else {
            onCursor(null, null)
        }
        repaint()
    }

    /** The screenshot's color under the point (the picker samples real pixels). */
    fun sample(p: Point): Color? {
        val shot = screenshot ?: return null
        val d = toDevice(p)
        if (d.x !in 0 until shot.width || d.y !in 0 until shot.height) return null
        return Color(shot.getRGB(d.x, d.y))
    }

    fun sampleCenter(node: UiNode): Color? {
        val shot = screenshot ?: return null
        val x = node.bounds.centerX.coerceIn(0, shot.width - 1)
        val y = node.bounds.centerY.coerceIn(0, shot.height - 1)
        return Color(shot.getRGB(x, y))
    }

    // ---- painting ----

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            paintBackground(g)
            val screen = screenRect() ?: return
            val h = hierarchy ?: return
            paintScreen(g, screen, h)
            if (grid) paintGrid(g, screen, h)
            overlay?.let { paintOverlay(g, screen, h, it) }
            if (auditMode) paintAudit(g)
            hovered?.takeIf { it != selected && !picker }?.let { paintHover(g, it, h) }
            selected?.let { paintSelected(g, it, h) }
            val sel = selected
            val hov = hovered
            if (sel != null && hov != null && hov != sel && !picker) paintRedlines(g, sel, hov, h)
            if (picker) mouse?.let { paintLoupe(g, it) }
        } finally {
            g.dispose()
        }
    }

    private fun paintBackground(g: Graphics2D) {
        g.color = AdbToolboxTheme.Colors.bg
        g.fillRect(0, 0, width, height)
        g.color = AdbToolboxTheme.Colors.border
        val step = JBUIScale.scale(16)
        var y = step / 2
        while (y < height) {
            var x = step / 2
            while (x < width) {
                g.fillRect(x, y, 1, 1)
                x += step
            }
            y += step
        }
    }

    private fun paintScreen(g: Graphics2D, screen: Rectangle, h: UiHierarchy) {
        val arc = JBUIScale.scale(20).toFloat()
        val shape = RoundRectangle2D.Float(screen.x.toFloat(), screen.y.toFloat(), screen.width.toFloat(), screen.height.toFloat(), arc, arc)
        g.color = Color(0, 0, 0, 40)
        g.fill(RoundRectangle2D.Float(screen.x + 0f, screen.y + 3f, screen.width.toFloat(), screen.height.toFloat(), arc, arc))
        val clip = g.clip
        g.clip(shape)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(screenshot, screen.x, screen.y, screen.width, screen.height, null)
        if (secure) {
            // FLAG_SECURE: the screenshot is black; the hierarchy stays inspectable as wireframes.
            g.color = AdbToolboxTheme.Colors.borderStrong
            g.stroke = BasicStroke(1f)
            h.root.descendantsAndSelf().filter { !it.bounds.isEmpty }.forEach { g.draw(toCanvas(it.bounds)) }
        }
        g.clip = clip
        g.color = AdbToolboxTheme.Colors.borderStrong
        g.stroke = BasicStroke(1f)
        g.draw(shape)
    }

    private fun paintGrid(g: Graphics2D, screen: Rectangle, h: UiHierarchy) {
        val accent = AdbToolboxTheme.Colors.accent
        g.color = Color(accent.red, accent.green, accent.blue, (255 * 0.22).toInt())
        g.stroke = BasicStroke(1f)
        val step = h.px(8.0) * scale
        if (step < 3) return
        var x = screen.x.toDouble()
        while (x <= screen.x + screen.width) {
            g.drawLine(x.roundToInt(), screen.y, x.roundToInt(), screen.y + screen.height)
            x += step
        }
        var y = screen.y.toDouble()
        while (y <= screen.y + screen.height) {
            g.drawLine(screen.x, y.roundToInt(), screen.x + screen.width, y.roundToInt())
            y += step
        }
    }

    /** Where the overlay lands, in canvas pixels. */
    private fun overlayRect(screen: Rectangle, h: UiHierarchy, o: DesignOverlay): Rectangle {
        val dpWidth = o.scale.factor?.let { o.image.width / it } ?: h.dp(h.root.bounds.width)
        val dpHeight = dpWidth * o.image.height / o.image.width
        val z = effectiveZoom
        return Rectangle(
            screen.x + (o.nudgeX * z).roundToInt(),
            screen.y + (o.nudgeY * z).roundToInt(),
            (dpWidth * z).roundToInt(),
            (dpHeight * z).roundToInt(),
        )
    }

    private fun paintOverlay(g: Graphics2D, screen: Rectangle, h: UiHierarchy, o: DesignOverlay) {
        val rect = overlayRect(screen, h, o)
        if (rect.width <= 0 || rect.height <= 0) return
        val image = if (o.difference) differenceImage(screen, rect, o) else o.image
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, o.opacity / 100f)
        g.drawImage(image, rect.x, rect.y, rect.width, rect.height, null)
        g.composite = AlphaComposite.SrcOver
    }

    /** |screenshot − overlay| per channel at canvas resolution, cached for the current geometry. */
    private fun differenceImage(screen: Rectangle, rect: Rectangle, o: DesignOverlay): BufferedImage {
        val key = "${rect}|${o.name}|${o.scale}|${screen}"
        differenceCache?.takeIf { it.first == key }?.let { return it.second }
        val base = BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB)
        base.createGraphics().apply {
            drawImage(screenshot, screen.x - rect.x, screen.y - rect.y, screen.width, screen.height, null)
            dispose()
        }
        val top = BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB)
        top.createGraphics().apply {
            drawImage(o.image, 0, 0, rect.width, rect.height, null)
            dispose()
        }
        val out = BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until rect.height) {
            for (x in 0 until rect.width) {
                val a = base.getRGB(x, y)
                val b = top.getRGB(x, y)
                val r = abs((a shr 16 and 0xff) - (b shr 16 and 0xff))
                val gg = abs((a shr 8 and 0xff) - (b shr 8 and 0xff))
                val bb = abs((a and 0xff) - (b and 0xff))
                out.setRGB(x, y, (r shl 16) or (gg shl 8) or bb)
            }
        }
        differenceCache = key to out
        return out
    }

    private fun paintAudit(g: Graphics2D) {
        val report = audit ?: return
        val badge = JBUIScale.scale(16)
        g.font = AdbToolboxTheme.Typography.mono.deriveFont(Font.BOLD, JBUIScale.scale(9f))
        report.stops.forEach { stop ->
            val r = toCanvas(stop.node.bounds)
            val problem = stop.issues.isNotEmpty()
            if (problem) {
                g.color = AdbToolboxTheme.Colors.red
                g.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(JBUIScale.scale(4f), JBUIScale.scale(3f)), 0f)
                g.draw(r)
            }
            g.color = if (problem) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.accent
            g.fillOval(r.x - badge / 3, r.y - badge / 3, badge, badge)
            g.color = Color.WHITE
            val text = stop.order.toString()
            val fm = g.fontMetrics
            g.drawString(text, r.x - badge / 3 + (badge - fm.stringWidth(text)) / 2, r.y - badge / 3 + (badge + fm.ascent - fm.descent) / 2)
        }
    }

    private fun paintHover(g: Graphics2D, node: UiNode, h: UiHierarchy) {
        val r = toCanvas(node.bounds)
        g.color = AdbToolboxTheme.Colors.accentBg
        g.fill(r)
        g.color = AdbToolboxTheme.Colors.accent
        g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(JBUIScale.scale(3f), JBUIScale.scale(2f)), 0f)
        g.draw(r)
        pill(g, "${node.shortClassName.ifEmpty { "View" }} · ${size(node, h)}", r.x, r.y - JBUIScale.scale(4), above = true, fill = AdbToolboxTheme.Colors.panel, text = AdbToolboxTheme.Colors.text)
    }

    private fun paintSelected(g: Graphics2D, node: UiNode, h: UiHierarchy) {
        val r = toCanvas(node.bounds)
        g.color = AdbToolboxTheme.Colors.accent
        g.stroke = BasicStroke(JBUIScale.scale(2f))
        g.draw(r)
        pill(g, size(node, h), r.x, r.y + r.height + JBUIScale.scale(4), above = false, fill = AdbToolboxTheme.Colors.accent, text = Color.WHITE)
    }

    private fun paintRedlines(g: Graphics2D, selected: UiNode, hovered: UiNode, h: UiHierarchy) {
        val cap = JBUIScale.scale(7) / 2
        g.color = AdbToolboxTheme.Colors.measure
        g.stroke = BasicStroke(1f)
        LayoutMeasurement.between(selected.bounds, hovered.bounds, h).forEach { line ->
            val fixed = padding + (line.fixed * scale).roundToInt()
            val from = padding + (line.from * scale).roundToInt()
            val to = padding + (line.to * scale).roundToInt()
            if (line.horizontal) {
                g.drawLine(from, fixed, to, fixed)
                g.drawLine(from, fixed - cap, from, fixed + cap)
                g.drawLine(to, fixed - cap, to, fixed + cap)
                pill(g, "${line.dp} dp", (from + to) / 2, fixed - JBUIScale.scale(3), above = true, fill = AdbToolboxTheme.Colors.measure, text = Color.WHITE, centered = true)
            } else {
                g.drawLine(fixed, from, fixed, to)
                g.drawLine(fixed - cap, from, fixed + cap, from)
                g.drawLine(fixed - cap, to, fixed + cap, to)
                pill(g, "${line.dp} dp", fixed + JBUIScale.scale(4), (from + to) / 2 + JBUIScale.scale(6), above = true, fill = AdbToolboxTheme.Colors.measure, text = Color.WHITE)
            }
            g.color = AdbToolboxTheme.Colors.measure
        }
    }

    private fun paintLoupe(g: Graphics2D, p: Point) {
        val color = sample(p) ?: return
        val swatch = JBUIScale.scale(18)
        val x = p.x + JBUIScale.scale(14)
        val y = p.y + JBUIScale.scale(14)
        val text = hex(color)
        g.font = AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(10f))
        val fm = g.fontMetrics
        val hint = "click to copy"
        val w = swatch + JBUIScale.scale(8) + maxOf(fm.stringWidth(text), fm.stringWidth(hint)) + JBUIScale.scale(10)
        val hgt = JBUIScale.scale(34)
        g.color = AdbToolboxTheme.Colors.panel
        g.fillRoundRect(x, y, w, hgt, JBUIScale.scale(8), JBUIScale.scale(8))
        g.color = AdbToolboxTheme.Colors.borderStrong
        g.drawRoundRect(x, y, w, hgt, JBUIScale.scale(8), JBUIScale.scale(8))
        g.color = color
        g.fillRect(x + JBUIScale.scale(6), y + (hgt - swatch) / 2, swatch, swatch)
        g.color = AdbToolboxTheme.Colors.text
        g.drawString(text, x + swatch + JBUIScale.scale(12), y + JBUIScale.scale(14))
        g.color = AdbToolboxTheme.Colors.textFaint
        g.drawString(hint, x + swatch + JBUIScale.scale(12), y + JBUIScale.scale(27))
    }

    private fun pill(g: Graphics2D, label: String, x: Int, y: Int, above: Boolean, fill: Color, text: Color, centered: Boolean = false) {
        g.font = AdbToolboxTheme.Typography.mono.deriveFont(Font.BOLD, JBUIScale.scale(10f))
        val fm = g.fontMetrics
        val w = fm.stringWidth(label) + JBUIScale.scale(10)
        val hgt = fm.height + JBUIScale.scale(2)
        val left = if (centered) x - w / 2 else x
        val top = if (above) y - hgt else y
        g.color = fill
        g.fillRoundRect(left, top, w, hgt, JBUIScale.scale(8), JBUIScale.scale(8))
        if (fill == AdbToolboxTheme.Colors.panel) {
            g.color = AdbToolboxTheme.Colors.borderStrong
            g.drawRoundRect(left, top, w, hgt, JBUIScale.scale(8), JBUIScale.scale(8))
        }
        g.color = text
        g.drawString(label, left + JBUIScale.scale(5), top + fm.ascent + JBUIScale.scale(1))
    }

    private fun size(node: UiNode, h: UiHierarchy) = "${h.dp(node.bounds.width).roundToInt()} × ${h.dp(node.bounds.height).roundToInt()} dp"

    private fun isBlack(image: BufferedImage): Boolean {
        val stepX = maxOf(1, image.width / 24)
        val stepY = maxOf(1, image.height / 48)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if (image.getRGB(x, y) and 0xffffff > 0x080808) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    companion object {
        fun hex(color: Color) = "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }
}
