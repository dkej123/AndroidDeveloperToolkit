package io.github.dkej123.devicecockpit.intellij.ui.common

import com.intellij.ui.scale.JBUIScale
import io.github.dkej123.devicecockpit.intellij.icons.AdbToolboxIcons
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.border.EmptyBorder

/** The colour a [ScreenToolButton] takes while its action is running (`st.*` in the prototype). */
enum class ToolButtonTone(val fill: () -> Color, val outline: () -> Color, val glyph: () -> Color) {
    BRAND({ AdbToolboxTheme.Colors.brandBg }, { AdbToolboxTheme.Colors.brandBorder }, { AdbToolboxTheme.Colors.brand }),
    RED({ AdbToolboxTheme.Colors.redBg }, { AdbToolboxTheme.Colors.redBorder }, { AdbToolboxTheme.Colors.red }),
    ACCENT({ AdbToolboxTheme.Colors.accentBg }, { AdbToolboxTheme.Colors.accentBorder }, { AdbToolboxTheme.Colors.accent }),
}

/** Which part of a split button this is: whole, or the main/caret half that shares an edge. */
enum class ToolButtonSegment { WHOLE, MAIN, CARET }

/**
 * The Screen section's toolbar button (design §3 "Screen"): 28px high, radius 5, 1px `border`,
 * a 16px glyph in `textDim`; 30px wide for an icon, 16px for a split button's caret, or padded
 * `0 10px 0 8px` with a 6px gap when it carries a [text] label (Inspect layout). While [tone] is
 * set the button takes that tone's fill, outline and glyph colour; otherwise hover fills `hover`.
 * Disabled renders at the design's 45%. A real [JButton], so focus, keyboard and accessibility
 * behave like the platform control; give it an accessible name, the glyph alone has none.
 */
class ScreenToolButton(
    glyph: Icon?,
    label: String? = null,
    private val segment: ToolButtonSegment = ToolButtonSegment.WHOLE,
) : JButton(label) {

    var glyph: Icon? = glyph
        set(value) {
            field = value
            repaint()
        }

    var tone: ToolButtonTone? = null
        set(value) {
            field = value
            foreground = value?.glyph?.invoke() ?: AdbToolboxTheme.Colors.text
            repaint()
        }

    /** A caret segment shows its open state (`accentBg`) while its panel or menu is showing. */
    var isOpen: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        isRolloverEnabled = true
        border = EmptyBorder(0, 0, 0, 0)
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.PLAIN, JBUIScale.scale(11.5f))
        foreground = AdbToolboxTheme.Colors.text
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val height = JBUIScale.scale(28)
        val width = when {
            segment == ToolButtonSegment.CARET -> JBUIScale.scale(16)
            text.isNullOrEmpty() -> JBUIScale.scale(30)
            else -> JBUIScale.scale(8) + JBUIScale.scale(16) + JBUIScale.scale(6) +
                getFontMetrics(font).stringWidth(text) + JBUIScale.scale(10)
        }
        return Dimension(width, height)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paint(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            if (!isEnabled) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, AdbToolboxTheme.States.disabledOpacity)
            }
            super.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val shape = outline()
            val active = tone
            val fill = when {
                active != null -> active.fill()
                isOpen -> AdbToolboxTheme.Colors.accentBg
                isEnabled && model.isRollover -> AdbToolboxTheme.Colors.hover
                else -> null
            }
            if (fill != null) {
                g2.color = fill
                g2.fill(shape)
            }
            g2.color = if (isFocusOwner) AdbToolboxTheme.Colors.accent else active?.outline?.invoke() ?: AdbToolboxTheme.Colors.border
            g2.stroke = BasicStroke(JBUIScale.scale(1f))
            g2.draw(shape)

            if (segment == ToolButtonSegment.CARET) {
                paintCaret(g2)
                return
            }
            val icon = glyph?.let { if (active != null) AdbToolboxIcons.tinted(it, active.glyph()) else it }
            val label = text.orEmpty()
            if (label.isEmpty()) {
                icon?.paintIcon(this, g2, (width - (icon.iconWidth)) / 2, (height - icon.iconHeight) / 2)
            } else {
                var x = JBUIScale.scale(8)
                if (icon != null) {
                    icon.paintIcon(this, g2, x, (height - icon.iconHeight) / 2)
                    x += icon.iconWidth + JBUIScale.scale(6)
                }
                g2.font = font
                g2.color = foreground
                val metrics = g2.fontMetrics
                g2.drawString(label, x, (height - metrics.height) / 2 + metrics.ascent)
            }
        } finally {
            g2.dispose()
        }
    }

    /** The 1px outline; a split button's halves square off (and the caret omits) the shared edge. */
    private fun outline(): java.awt.Shape {
        val stroke = JBUIScale.scale(1f)
        val arc = (AdbToolboxTheme.Radii.button * 2).toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        val half = stroke / 2
        return when (segment) {
            ToolButtonSegment.WHOLE -> RoundRectangle2D.Float(half, half, w - stroke, h - stroke, arc, arc)
            ToolButtonSegment.MAIN -> roundedOn(half, half, w - half, h - half, arc / 2, left = true)
            ToolButtonSegment.CARET -> roundedOn(-half, half, w - half, h - half, arc / 2, left = false)
        }
    }

    private fun roundedOn(x0: Float, y0: Float, x1: Float, y1: Float, r: Float, left: Boolean): Path2D.Float =
        Path2D.Float().apply {
            if (left) {
                moveTo(x1, y0); lineTo(x0 + r, y0); quadTo(x0, y0, x0, y0 + r)
                lineTo(x0, y1 - r); quadTo(x0, y1, x0 + r, y1); lineTo(x1, y1); closePath()
            } else {
                moveTo(x0, y0); lineTo(x1 - r, y0); quadTo(x1, y0, x1, y0 + r)
                lineTo(x1, y1 - r); quadTo(x1, y1, x1 - r, y1); lineTo(x0, y1)
            }
        }

    /** `caretIconStyle`: a 5px chevron, 1.3px `textDim` strokes, pointing down. */
    private fun paintCaret(g2: Graphics2D) {
        val size = JBUIScale.scale(5f)
        val cx = width / 2f
        val cy = height / 2f
        g2.color = AdbToolboxTheme.Colors.textDim
        g2.stroke = BasicStroke(JBUIScale.scale(1.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(
            Path2D.Float().apply {
                moveTo(cx - size / 1.6f, cy - size / 3.2f)
                lineTo(cx, cy + size / 3.2f)
                lineTo(cx + size / 1.6f, cy - size / 3.2f)
            },
        )
    }
}
