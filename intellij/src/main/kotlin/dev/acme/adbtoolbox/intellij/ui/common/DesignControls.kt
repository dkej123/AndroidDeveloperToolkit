package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager2
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder

/**
 * Button roles from the plugin prototype (`design/designs/ADB Toolbox Plugin.dc.html`: `primaryBtn`,
 * `secondaryBtn`, `dangerBtn`, `link`). Heights, horizontal padding, weight and colors are the
 * prototype's values; every pixel value goes through [JBUI.scale].
 */
enum class DesignButtonStyle { PRIMARY, SECONDARY, DANGER, LINK }

/**
 * A text-only [JButton] painted with the supplied button chrome: rounded 5px fill/outline, fixed
 * control height, and the design system's 45% disabled opacity (`states.disabledOpacity`) instead of
 * the look-and-feel's grey disabled text. It stays a real [JButton] so keyboard activation, focus
 * traversal, accessibility and `doClick()` behave exactly like the platform control.
 */
class DesignButton(text: String, style: DesignButtonStyle) : JButton(text) {

    var style: DesignButtonStyle = style
        set(value) {
            field = value
            applyStyle()
        }

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        isRolloverEnabled = true
        applyStyle()
    }

    private fun applyStyle() {
        font = when (style) {
            DesignButtonStyle.PRIMARY -> AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11.5f))
            DesignButtonStyle.SECONDARY -> AdbToolboxTheme.Typography.body.deriveFont(Font.PLAIN, JBUI.scale(11.5f))
            DesignButtonStyle.DANGER -> AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11f))
            DesignButtonStyle.LINK -> AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11f))
        }
        foreground = when (style) {
            DesignButtonStyle.PRIMARY -> Color.WHITE
            DesignButtonStyle.SECONDARY -> AdbToolboxTheme.Colors.text
            DesignButtonStyle.DANGER -> AdbToolboxTheme.Colors.red
            DesignButtonStyle.LINK -> AdbToolboxTheme.Colors.accent
        }
        border = EmptyBorder(0, 0, 0, 0)
        revalidate()
        repaint()
    }

    private val horizontalPadding: Int
        get() = when (style) {
            DesignButtonStyle.PRIMARY -> JBUI.scale(12)
            DesignButtonStyle.SECONDARY -> JBUI.scale(10)
            DesignButtonStyle.DANGER -> JBUI.scale(9)
            DesignButtonStyle.LINK -> 0
        }

    private val controlHeight: Int
        get() = when (style) {
            DesignButtonStyle.PRIMARY -> AdbToolboxTheme.Sizes.primaryButton
            DesignButtonStyle.SECONDARY -> AdbToolboxTheme.Sizes.secondaryButton
            DesignButtonStyle.DANGER -> AdbToolboxTheme.Sizes.field
            DesignButtonStyle.LINK -> getFontMetrics(font).height + JBUI.scale(2)
        }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val metrics = getFontMetrics(font)
        return Dimension(metrics.stringWidth(text.orEmpty()) + horizontalPadding * 2, controlHeight)
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
            val arc = (AdbToolboxTheme.Radii.button * 2).toFloat()
            val stroke = JBUI.scale(1f)
            val outline = RoundRectangle2D.Float(stroke / 2, stroke / 2, width - stroke, height - stroke, arc, arc)
            val hovered = isEnabled && model.isRollover
            when (style) {
                DesignButtonStyle.PRIMARY -> {
                    g2.color = AdbToolboxTheme.Colors.accent
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
                }
                DesignButtonStyle.SECONDARY -> {
                    if (hovered) {
                        g2.color = AdbToolboxTheme.Colors.hover
                        g2.fill(outline)
                    }
                    g2.color = AdbToolboxTheme.Colors.borderStrong
                    g2.stroke = BasicStroke(stroke)
                    g2.draw(outline)
                }
                DesignButtonStyle.DANGER -> {
                    if (hovered) {
                        g2.color = AdbToolboxTheme.Colors.redBg
                        g2.fill(outline)
                    }
                    g2.color = AdbToolboxTheme.Colors.redBorder
                    g2.stroke = BasicStroke(stroke)
                    g2.draw(outline)
                }
                DesignButtonStyle.LINK -> Unit
            }
            if (isFocusOwner && style != DesignButtonStyle.LINK) {
                g2.color = AdbToolboxTheme.Colors.accent
                g2.stroke = BasicStroke(stroke)
                g2.draw(outline)
            }
            g2.font = font
            g2.color = foreground
            val metrics = g2.fontMetrics
            val label = text.orEmpty()
            val textX = (width - metrics.stringWidth(label)) / 2
            val textY = (height - metrics.height) / 2 + metrics.ascent
            g2.drawString(label, textX, textY)
            if (isFocusOwner && style == DesignButtonStyle.LINK) {
                g2.drawLine(textX, textY + JBUI.scale(1), textX + metrics.stringWidth(label), textY + JBUI.scale(1))
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * Horizontal flex row: children are laid out left to right with a fixed [gap] and are vertically
 * centered in the row (the prototype's `display:flex; align-items:center`). At most one child added
 * with [FILL] takes the remaining width (the prototype's `flex: 1` / spacer), which pushes every
 * later child to the trailing edge; it is the first child to shrink when space runs out. A child
 * added with [SHRINK] keeps its preferred width but gives up width (ellipsising) once the fill is
 * exhausted, so trailing actions always stay inside the row.
 */
class FlexRowLayout(private val gap: Int) : LayoutManager2 {
    private var fill: Component? = null
    private var shrink: Component? = null

    override fun addLayoutComponent(comp: Component, constraints: Any?) {
        if (constraints == FILL) fill = comp
        if (constraints == SHRINK) shrink = comp
    }

    override fun addLayoutComponent(name: String?, comp: Component) {
        addLayoutComponent(comp, name)
    }

    override fun removeLayoutComponent(comp: Component) {
        if (fill === comp) fill = null
        if (shrink === comp) shrink = null
    }

    override fun preferredLayoutSize(parent: Container): Dimension = size(parent) { it.preferredSize }

    override fun minimumLayoutSize(parent: Container): Dimension = size(parent) { if (it === fill) Dimension(0, it.minimumSize.height) else it.preferredSize }

    override fun maximumLayoutSize(target: Container): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

    override fun getLayoutAlignmentX(target: Container): Float = 0f

    override fun getLayoutAlignmentY(target: Container): Float = 0.5f

    override fun invalidateLayout(target: Container) = Unit

    private fun size(parent: Container, measure: (Component) -> Dimension): Dimension {
        val visible = parent.components.filter(Component::isVisible)
        val insets = parent.insets
        var width = 0
        var height = 0
        visible.forEachIndexed { index, component ->
            val size = measure(component)
            width += size.width + if (index > 0) gap else 0
            height = maxOf(height, size.height)
        }
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun layoutContainer(parent: Container) {
        val visible = parent.components.filter(Component::isVisible)
        val insets = parent.insets
        val available = parent.width - insets.left - insets.right
        val innerHeight = parent.height - insets.top - insets.bottom
        val fixedWidth = visible.filter { it !== fill }.sumOf { it.preferredSize.width } +
            gap * (visible.size - 1).coerceAtLeast(0)
        val fillWidth = (available - fixedWidth).coerceAtLeast(0)
        val overflow = (fixedWidth - available).coerceAtLeast(0)
        var x = insets.left
        visible.forEach { component ->
            val preferred = component.preferredSize
            val width = when {
                component === fill -> fillWidth
                component === shrink -> (preferred.width - overflow).coerceAtLeast(0)
                else -> preferred.width
            }
            val height = if (component === fill) minOf(innerHeight, maxOf(preferred.height, 0)) else minOf(preferred.height, innerHeight)
            val y = insets.top + (innerHeight - height) / 2
            component.setBounds(x, y, width, height)
            x += width + gap
        }
    }

    companion object {
        const val FILL = "fill"
        const val SHRINK = "shrink"
    }
}

/** A transparent [FlexRowLayout] row; [fill] (a child or a [flexSpacer]) takes the remaining width. */
fun flexRow(gap: Int, vararg children: Component, fill: Component? = null, shrink: Component? = null): JPanel =
    JPanel(FlexRowLayout(gap)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        children.forEach { child ->
            when {
                child === fill -> add(child, FlexRowLayout.FILL)
                child === shrink -> add(child, FlexRowLayout.SHRINK)
                else -> add(child)
            }
        }
    }

/** An empty flexible component for [flexRow] that pushes the following children to the trailing edge. */
fun flexSpacer(): JComponent = Box.createRigidArea(Dimension(0, 0)) as JComponent

/**
 * Wrapping secondary text (the prototype's `helpText`/`emptyBody`: multi-line, left-aligned and
 * never ellipsised). A non-editable, non-focusable [JTextArea] is the platform's width-following
 * word-wrapping text primitive; it keeps its own accessible text.
 */
class WrappingText(text: String, font: Font, color: Color) : JTextArea(text) {
    init {
        this.font = font
        foreground = color
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isFocusable = false
        isOpaque = false
        border = EmptyBorder(0, 0, 0, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        highlighter = null
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * Centered wrapping copy (the prototype's empty-state `emptyBodyStyle`: `text-align: center` with a
 * `max-width`). It follows the width its column gives it, capped at [maxWidth].
 */
class CenteredWrappingText(
    text: String,
    font: Font,
    color: Color,
    private val maxWidth: () -> Int,
) : javax.swing.JTextPane() {
    init {
        this.font = font
        foreground = color
        isEditable = false
        isFocusable = false
        isOpaque = false
        border = EmptyBorder(0, 0, 0, 0)
        highlighter = null
        alignmentX = Component.CENTER_ALIGNMENT
        putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        this.text = text
        val center = javax.swing.text.SimpleAttributeSet().also {
            javax.swing.text.StyleConstants.setAlignment(it, javax.swing.text.StyleConstants.ALIGN_CENTER)
        }
        styledDocument.setParagraphAttributes(0, styledDocument.length, center, false)
        getAccessibleContext().accessibleName = text
    }

    override fun getMaximumSize(): Dimension = Dimension(maxWidth(), preferredSize.height)
}

/** A panel painting a rounded [fill] and optional 1px [outline] — banners, pills and selected rows. */
open class RoundedSurface(
    var fill: Color?,
    var outline: Color?,
    private val radius: () -> Int = { AdbToolboxTheme.Radii.button },
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = (radius() * 2).toFloat().coerceAtMost(height.toFloat())
            val stroke = JBUI.scale(1f)
            fill?.let {
                g2.color = it
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            }
            outline?.let {
                g2.color = it
                g2.stroke = BasicStroke(stroke)
                g2.draw(RoundRectangle2D.Float(stroke / 2, stroke / 2, width - stroke, height - stroke, arc, arc))
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * Shared section vocabulary from the prototype (`sectionStyle`, `sectionHeaderStyle`, `helpText`):
 * a view section is `padding: 10px 0 12px` with a 1px bottom border and a 6px gap between rows;
 * rows carry the 10px horizontal inset themselves.
 */
object DesignSections {
    fun section(vararg children: Component): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(10, 0, 12, 0),
        )
        children.forEachIndexed { index, child ->
            if (index > 0) add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3))
            if (child is JComponent) child.alignmentX = Component.LEFT_ALIGNMENT
            add(child)
        }
    }

    fun titleLabel(text: String): JBLabel = JBLabel(text).apply {
        font = AdbToolboxTheme.Typography.sectionTitle
        foreground = AdbToolboxTheme.Colors.text
    }

    fun metaLabel(text: String = "—", size: Float = 10f): JBLabel = JBLabel(text).apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(size))
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    fun header(title: Component, trailing: Component?): JPanel {
        val row = if (trailing != null) {
            val spacer = flexSpacer()
            flexRow(AdbToolboxTheme.Spacing.s4, title, spacer, trailing, fill = spacer)
        } else {
            flexRow(AdbToolboxTheme.Spacing.s4, title)
        }
        return row.apply { border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset) }
    }

    fun helpText(text: String): WrappingText =
        WrappingText(text, AdbToolboxTheme.Typography.caption, AdbToolboxTheme.Colors.textFaint).apply {
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset, AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset)
        }

    /** Applies the section's 10px horizontal inset to a row. */
    fun inset(component: JComponent): JComponent = component.apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        alignmentX = Component.LEFT_ALIGNMENT
    }
}
