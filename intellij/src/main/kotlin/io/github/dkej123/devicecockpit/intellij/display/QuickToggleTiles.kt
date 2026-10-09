package io.github.dkej123.devicecockpit.intellij.display

import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.ToggleSwitch
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * One Quick toggles tile (`design/README.md` §5.3, 2026-09-30 redesign): label and compact switch
 * on top, the mono device readback below. On = `accentBg` fill + `accentBorder`, bold label and
 * accent value; off = transparent with a `border` outline and the hover fill. A click anywhere on the
 * tile flips [toggle]. Children are added label → value → switch so the value stays the label's next
 * sibling for the E2E `valueAfterCaption` lookup.
 */
internal class QuickToggleTile(
    label: String,
    val toggle: ToggleSwitch,
    val valueLabel: JBLabel,
) : JPanel(null) {
    // Labels are never truncated (design §5.3 extended grid): an HTML label wraps at its width; its
    // accessible name stays the plain label for assistive tech and the E2E caption lookup.
    private val labelComponent = JBLabel("<html>${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(label)}</html>").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(JBUIScale.scale(11.5f))
        foreground = AdbToolboxTheme.Colors.text
        getAccessibleContext().accessibleName = label
        verticalAlignment = javax.swing.SwingConstants.TOP
    }

    /** The label's height when wrapped into [width]. */
    private fun labelHeight(width: Int): Int {
        val view = labelComponent.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) as? javax.swing.text.View
            ?: return labelComponent.preferredSize.height
        view.setSize(maxOf(1, width).toFloat(), 0f)
        return kotlin.math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS).toDouble()).toInt()
    }

    /** The tile's height at [width], with the label wrapped (rows of the grid use the tallest). */
    fun heightFor(width: Int): Int {
        val labelWidth = width - JBUIScale.scale(9) - JBUIScale.scale(8) - JBUIScale.scale(6) - toggle.preferredSize.width
        val top = maxOf(labelHeight(labelWidth), toggle.preferredSize.height + JBUIScale.scale(2))
        return JBUIScale.scale(7) + top + JBUIScale.scale(3) + valueLabel.preferredSize.height + JBUIScale.scale(7)
    }
    private var hovered = false

    init {
        isOpaque = false
        toggle.getAccessibleContext().accessibleName = label
        valueLabel.font = AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(9.5f))
        add(labelComponent)
        add(valueLabel)
        add(toggle)
        layout = TileLayout()
        toggle.addItemListener { refresh() }
        toggle.addPropertyChangeListener("enabled") { refresh() }
        // Labels with a tooltip swallow mouse events, so the children forward clicks/hover too.
        val tileMouse = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (toggle.isEnabled) toggle.doClick(0)
            }

            override fun mouseEntered(event: MouseEvent) = setHovered(true)

            override fun mouseExited(event: MouseEvent) = setHovered(getMousePosition(true) != null)
        }
        listOf(this, labelComponent, valueLabel).forEach { it.addMouseListener(tileMouse) }
        toggle.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) = setHovered(true)

            override fun mouseExited(event: MouseEvent) = setHovered(getMousePosition(true) != null)
        })
        refresh()
    }

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        repaint()
    }

    private fun refresh() {
        val on = toggle.isSelected
        labelComponent.font = labelComponent.font.deriveFont(if (on) Font.BOLD else Font.PLAIN)
        valueLabel.foreground = if (on) AdbToolboxTheme.Colors.accent else AdbToolboxTheme.Colors.textFaint
        cursor = Cursor.getPredefinedCursor(if (toggle.isEnabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        toolTipText = toggle.toolTipText
        repaint()
    }

    /** n/a and blocked tiles stay visible at the design system's 45% (design §5.3). */
    override fun paint(graphics: Graphics) {
        if (isEnabled) return super.paint(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, AdbToolboxTheme.States.disabledOpacity)
            super.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUIScale.scale(6) * 2
            val on = toggle.isSelected
            val fill = when {
                on -> AdbToolboxTheme.Colors.accentBg
                hovered && toggle.isEnabled -> AdbToolboxTheme.Colors.hover
                else -> null
            }
            if (fill != null) {
                g2.color = fill
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            }
            g2.color = if (on) AdbToolboxTheme.Colors.accentBorder else AdbToolboxTheme.Colors.border
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    /** `padding: 7px 8px 7px 9px`, top row gap 6, 3px between the top row and the value line. */
    private inner class TileLayout : LayoutManager {
        private val top get() = JBUIScale.scale(7)
        private val right get() = JBUIScale.scale(8)
        private val left get() = JBUIScale.scale(9)
        private val rowGap get() = JBUIScale.scale(6)
        private val lineGap get() = JBUIScale.scale(3)

        private fun labelWidth(width: Int) = maxOf(0, width - left - rowGap - toggle.preferredSize.width - right)

        private fun topRowHeight(width: Int) = maxOf(labelHeight(labelWidth(width)), toggle.preferredSize.height + JBUIScale.scale(2))

        override fun preferredLayoutSize(parent: Container): Dimension {
            val width = if (parent.width > 0) parent.width else JBUIScale.scale(150)
            return Dimension(
                left + rowGap + toggle.preferredSize.width + right + JBUIScale.scale(60),
                top + topRowHeight(width) + lineGap + valueLabel.preferredSize.height + top,
            )
        }

        override fun minimumLayoutSize(parent: Container): Dimension =
            Dimension(left + rowGap + toggle.preferredSize.width + right, preferredLayoutSize(parent).height)

        override fun layoutContainer(parent: Container) {
            val rowHeight = topRowHeight(parent.width)
            val switchSize = toggle.preferredSize
            val switchX = parent.width - right - switchSize.width
            // The track stays top-aligned with the first label line (margin-top 2px).
            toggle.setBounds(switchX, top + JBUIScale.scale(2), switchSize.width, switchSize.height)
            val labelWidth = labelWidth(parent.width)
            labelComponent.setBounds(left, top, labelWidth, labelHeight(labelWidth))
            valueLabel.setBounds(
                left,
                top + rowHeight + lineGap,
                maxOf(0, parent.width - left - right),
                valueLabel.preferredSize.height,
            )
        }

        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit
    }
}

/**
 * `grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))` with a 5px gap: as many equal
 * columns as fit at 150px each (at least one), rows sized to the tallest tile. The preferred height
 * depends on the width, so a width change that alters the column count re-publishes the height the
 * same way [io.github.dkej123.devicecockpit.intellij.ui.common.PresetChipRow] does.
 */
internal class QuickToggleGrid(tiles: List<QuickToggleTile>) : JPanel(null) {
    private var publishedColumns = -1
    private var publishedWidth = -1

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        tiles.forEach(::add)
        layout = GridLayout()
    }

    internal fun columnsFor(width: Int): Int {
        val insets = insets
        val available = width - insets.left - insets.right
        val gap = JBUIScale.scale(5)
        return maxOf(1, (available + gap) / (JBUIScale.scale(150) + gap))
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (width <= 0) return
        val columns = columnsFor(width)
        // Wrapped labels make the row height depend on the width, not only on the column count.
        if (columns != publishedColumns || width != publishedWidth) {
            publishedColumns = columns
            publishedWidth = width
            // Not now: this runs inside the parent's layout pass, and invalidating a BoxLayout
            // parent mid-pass nulls its child cache (NPE "this.xChildren is null", E2E 2026-10-01).
            javax.swing.SwingUtilities.invokeLater {
                var ancestor: java.awt.Container? = this
                while (ancestor != null) {
                    ancestor.invalidate()
                    ancestor = ancestor.parent
                }
                revalidate()
            }
        }
    }

    private inner class GridLayout : LayoutManager {
        private val gap get() = JBUIScale.scale(5)

        private fun widthOf(parent: Container) =
            if (parent.width > 0) parent.width else parent.parent?.width ?: 0

        private fun cellWidth(parent: Container, width: Int): Int {
            val insets = parent.insets
            val columns = columnsFor(width)
            return (width - insets.left - insets.right - (columns - 1) * gap) / columns
        }

        private fun rowHeight(parent: Container, width: Int): Int {
            val cell = cellWidth(parent, width)
            return parent.components.maxOfOrNull { (it as? QuickToggleTile)?.heightFor(cell) ?: it.preferredSize.height } ?: 0
        }

        override fun preferredLayoutSize(parent: Container): Dimension {
            val insets = parent.insets
            val count = parent.componentCount
            if (count == 0) return Dimension(insets.left + insets.right, insets.top + insets.bottom)
            val columns = columnsFor(widthOf(parent))
            val rows = (count + columns - 1) / columns
            val width = widthOf(parent).takeIf { it > 0 } ?: (insets.left + insets.right + JBUIScale.scale(150))
            return Dimension(
                insets.left + insets.right + JBUIScale.scale(150),
                insets.top + insets.bottom + rows * rowHeight(parent, width) + (rows - 1) * gap,
            )
        }

        override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            val columns = columnsFor(parent.width)
            val available = parent.width - insets.left - insets.right
            val cellWidth = (available - (columns - 1) * gap) / columns
            val cellHeight = rowHeight(parent, parent.width)
            parent.components.forEachIndexed { index, component ->
                val column = index % columns
                val row = index / columns
                component.setBounds(
                    insets.left + column * (cellWidth + gap),
                    insets.top + row * (cellHeight + gap),
                    cellWidth,
                    cellHeight,
                )
            }
        }

        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit
    }
}
