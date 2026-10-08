package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * `dispGridStyle`: `grid-template-columns: repeat(auto-fit, minmax(130px, 1fr))`, gap 8 — columns
 * share the width equally and wrap to one per row once a column would drop below [minColumn].
 */
class ResponsiveColumnsLayout(
    private val minColumn: () -> Int = { JBUIScale.scale(130) },
    private val gap: () -> Int = { AdbToolboxTheme.Spacing.s4 },
) : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component) = Unit

    override fun removeLayoutComponent(comp: Component) = Unit

    private fun columnsFor(parent: Container, width: Int): Int {
        val count = parent.componentCount.coerceAtLeast(1)
        val fits = ((width + gap()) / (minColumn() + gap())).coerceAtLeast(1)
        return minOf(count, fits)
    }

    private fun rows(parent: Container, columns: Int): List<List<Component>> = parent.components.toList().chunked(columns)

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val width = (parent.width - insets.left - insets.right).takeIf { it > 0 }
            ?: (parent.componentCount * minColumn() + gap() * (parent.componentCount - 1))
        val rowHeights = rows(parent, columnsFor(parent, width)).map { row -> row.maxOf { it.preferredSize.height } }
        val height = rowHeights.sum() + gap() * (rowHeights.size - 1).coerceAtLeast(0)
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, preferredLayoutSize(parent).height)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val width = parent.width - insets.left - insets.right
        val columns = columnsFor(parent, width)
        val columnWidth = (width - gap() * (columns - 1)) / columns
        var y = insets.top
        rows(parent, columns).forEach { row ->
            val height = row.maxOf { it.preferredSize.height }
            row.forEachIndexed { index, child -> child.setBounds(insets.left + index * (columnWidth + gap()), y, columnWidth, height) }
            y += height + gap()
        }
    }
}

/** `dispColStyle`: a 10.5px `textDim` caption 3px above its 24px control. */
fun labeledColumn(caption: String, control: JComponent): JPanel = verticalStackWithGap(JBUIScale.scale(3),
    JBLabel(caption).apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.textDim
        labelFor = control
    },
    control.apply { preferredSize = Dimension(JBUIScale.scale(130), AdbToolboxTheme.Sizes.field) },
)

/** A [ResponsiveColumnsLayout] row of [columns] with the section's 10px inset. */
fun responsiveColumns(vararg columns: JComponent): JPanel = object : JPanel(ResponsiveColumnsLayout()) {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}.apply {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = com.intellij.util.ui.JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
    columns.forEach(::add)
}

/** A 24px platform combo for a [labeledColumn]; every item — `null` included — renders through [label]. */
fun <T> designCombo(label: (T) -> String): ComboBox<T> = ComboBox<T>().apply {
    renderer = textRenderer(label)
    font = AdbToolboxTheme.Typography.body.deriveFont(JBUIScale.scale(11.5f))
}

/**
 * A list renderer showing [label] for each item, `null` included. A subclass rather than
 * `SimpleListCellRenderer.create(...)`, which 2026.x schedules for removal.
 */
fun <T> textRenderer(label: (T) -> String): com.intellij.ui.SimpleListCellRenderer<T> =
    object : com.intellij.ui.SimpleListCellRenderer<T>() {
        override fun customize(list: javax.swing.JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = label(value)
        }
    }

private fun verticalStackWithGap(gap: Int, vararg children: Component): JPanel =
    JPanel(VerticalStackLayout { gap }).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        children.forEach(::add)
    }
