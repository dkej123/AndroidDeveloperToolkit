package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.intellij.ui.common.*
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.plaf.basic.BasicScrollBarUI

internal object DeepLinkVisuals {
    fun ui(size: Float, bold: Boolean = false): Font = DeviceCockpitTheme.Typography.body.deriveFont(if (bold) Font.BOLD else Font.PLAIN, JBUIScale.scale(size))
    fun mono(size: Float, bold: Boolean = false): Font = DeviceCockpitTheme.Typography.mono.deriveFont(if (bold) Font.BOLD else Font.PLAIN, JBUIScale.scale(size))
    fun label(text: String, size: Float = 10.5f, bold: Boolean = false, color: Color = DeviceCockpitTheme.Colors.textDim) = JLabel(text).apply {
        font = ui(size, bold); foreground = color
    }
    fun caption(text: String) = label(text, 9.5f, true).apply {
        font = font.deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to 0.05f))
        border = JBUI.Borders.empty(12, 0, 5, 0)
    }
    fun button(text: String, style: DesignButtonStyle, action: () -> Unit): DesignButton = DesignButton(text, style).apply {
        font = ui(11f, true)
        val padding = if (style == DesignButtonStyle.LINK) 0 else 20
        preferredSize = Dimension(getFontMetrics(font).stringWidth(text) + JBUI.scale(padding), JBUI.scale(24))
        addActionListener { action() }
    }
    fun surface(fill: Color, outline: Color, radius: Int) = object : RoundedSurface(fill, outline, { JBUI.scale(radius) }) {
        override fun paintChildren(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.clip(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), JBUI.scale(radius * 2).toFloat(), JBUI.scale(radius * 2).toFloat()))
                super.paintChildren(g)
            } finally { g.dispose() }
        }
    }
    fun scroll(view: Component): JScrollPane = JScrollPane(view).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        listOf(verticalScrollBar, horizontalScrollBar).forEach { bar ->
            bar.ui = object : BasicScrollBarUI() {
                override fun createDecreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                override fun createIncreaseButton(orientation: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                override fun paintTrack(g: Graphics, c: JComponent, bounds: Rectangle) {}
                override fun paintThumb(graphics: Graphics, c: JComponent, bounds: Rectangle) {
                    val g = graphics.create() as Graphics2D
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g.color = DeviceCockpitTheme.Colors.borderStrong
                        g.fillRoundRect(bounds.x + 1, bounds.y + 1, (bounds.width - 2).coerceAtLeast(1), (bounds.height - 2).coerceAtLeast(1), JBUI.scale(6), JBUI.scale(6))
                    } finally { g.dispose() }
                }
            }
            bar.isOpaque = false
            bar.preferredSize = Dimension(JBUI.scale(6), JBUI.scale(6))
            bar.unitIncrement = JBUI.scale(16)
        }
    }
}

internal class DeepLinkField(placeholder: String, height: Int = 28) : JBTextField() {
    var error = false
        set(value) { field = value; repaint() }
    init {
        name = placeholder
        emptyText.text = placeholder
        isOpaque = false
        foreground = DeviceCockpitTheme.Colors.text
        caretColor = DeviceCockpitTheme.Colors.text
        font = DeepLinkVisuals.mono(12f)
        preferredSize = Dimension(0, JBUI.scale(height))
        minimumSize = Dimension(0, JBUI.scale(height))
        border = object : AbstractBorder() {
            override fun getBorderInsets(c: Component) = JBUI.insets(0, 7)
            override fun getBorderInsets(c: Component, insets: Insets): Insets = getBorderInsets(c).also {
                insets.set(it.top, it.left, it.bottom, it.right)
            }
            override fun paintBorder(c: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = if (error) DeviceCockpitTheme.Colors.red else if (hasFocus()) DeviceCockpitTheme.Colors.accent else DeviceCockpitTheme.Colors.borderStrong
                    g.stroke = BasicStroke(JBUIScale.scale(1f))
                    g.drawRoundRect(x + 1, y + 1, width - 2, height - 2, JBUI.scale(8), JBUI.scale(8))
                } finally { g.dispose() }
            }
        }
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) = repaint()
            override fun focusLost(e: FocusEvent?) = repaint()
        })
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = DeviceCockpitTheme.Colors.field
            g.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }
}

/**
 * Rows from the top at their preferred height for the current width. GridBagLayout, short of height,
 * fell back to minimum sizes (the Recent table collapsed) and centred the rest.
 */
internal class DeepLinkStack : JPanel(VerticalStackLayout { 0 }), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height - JBUI.scale(16)).coerceAtLeast(1)
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    init { isOpaque = false }
    fun row(component: JComponent) {
        add(component)
    }
}

internal class DeepLinkPackageButton : JButton() {
    private var label = "Any app"
    private var subtitle = "System chooser"
    private var tile: Icon? = null
    var popupOpen = false
        set(value) { field = value; repaint() }
    init {
        name = "deviceDeepLinkPackage"
        toolTipText = "Which app should receive the link"
        isOpaque = false; isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        isRolloverEnabled = true
        preferredSize = Dimension(0, JBUI.scale(36))
        minimumSize = Dimension(0, JBUI.scale(36))
    }
    fun showChoice(label: String, subtitle: String, icon: Icon) {
        this.label = label; this.subtitle = subtitle; this.tile = icon
        getAccessibleContext().accessibleName = "Open in: $label"
        repaint()
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = DeviceCockpitTheme.Colors.field
            g.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            g.color = if (popupOpen || hasFocus()) DeviceCockpitTheme.Colors.accent else DeviceCockpitTheme.Colors.borderStrong
            g.stroke = BasicStroke(JBUIScale.scale(1f))
            g.drawRoundRect(1, 1, width - 2, height - 2, JBUI.scale(8), JBUI.scale(8))
            tile?.paintIcon(this, g, JBUI.scale(8), (height - JBUI.scale(16)) / 2)
            val x = JBUI.scale(32)
            val available = (width - x - JBUI.scale(26)).coerceAtLeast(0)
            g.font = DeepLinkVisuals.ui(11.5f, true); g.color = DeviceCockpitTheme.Colors.text
            g.drawString(ellipsize(label, g.fontMetrics, available), x, JBUI.scale(15))
            g.font = DeepLinkVisuals.mono(10f); g.color = DeviceCockpitTheme.Colors.textDim
            g.drawString(ellipsize(subtitle, g.fontMetrics, available), x, JBUI.scale(28))
            val cx = width - JBUI.scale(12); val cy = height / 2
            g.stroke = BasicStroke(JBUIScale.scale(1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(cx - JBUI.scale(3), cy - JBUI.scale(1), cx, cy + JBUI.scale(2))
            g.drawLine(cx, cy + JBUI.scale(2), cx + JBUI.scale(3), cy - JBUI.scale(1))
        } finally { g.dispose() }
    }
}

internal fun ellipsize(value: String, metrics: FontMetrics, width: Int): String {
    if (metrics.stringWidth(value) <= width) return value
    var end = value.length
    while (end > 0 && metrics.stringWidth(value.substring(0, end) + "…") > width) end--
    return if (end == 0 && metrics.stringWidth("…") > width) "" else value.substring(0, end) + "…"
}

/** PlainView reads this font metric, so code lines retain the prototype's 1.6 line height. */
internal class DeepLinkCommandArea : JTextArea() {
    override fun getFontMetrics(font: Font): FontMetrics {
        val base = super.getFontMetrics(font)
        return object : FontMetrics(font) {
            override fun getHeight() = JBUI.scale(18)
            override fun getAscent() = base.ascent
            override fun getDescent() = base.descent
            override fun getLeading() = (height - ascent - descent).coerceAtLeast(0)
            override fun charWidth(c: Char) = base.charWidth(c)
            override fun charWidth(c: Int) = base.charWidth(c)
            override fun stringWidth(text: String) = base.stringWidth(text)
            override fun charsWidth(data: CharArray, off: Int, len: Int) = base.charsWidth(data, off, len)
        }
    }
}
