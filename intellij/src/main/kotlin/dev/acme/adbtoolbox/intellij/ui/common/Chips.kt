package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.ui.components.JBPanel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.border.AbstractBorder

data class PresetChipChoice<T : Any>(
    val value: T,
    val label: String,
    val isDefault: Boolean = false,
    val kind: PresetChipKind = PresetChipKind.PRESET,
    /** Segmented style only: the share of the track (the process limit's "Standard" takes 2). */
    val segmentWeight: Int = if (isDefault) 2 else 1,
    /** Segmented style only: mono for values (process-limit numbers), UI font for words (rotation). */
    val monospaced: Boolean = !isDefault,
)

enum class PresetChipKind { PRESET, CUSTOM }

/**
 * [WRAP]: free-standing chips that wrap onto new rows. [SEGMENTED]: one full-width `field` track
 * (`borderStrong` outline, 2px padding and gap) whose chips share the width — the default choice two
 * parts, every other one part — as in the Quick toggles "Background process limit" control.
 */
enum class PresetChipRowStyle { WRAP, SEGMENTED }

/**
 * The compact, wrapping single-choice row supplied by the design-system prototype section 06.
 * It intentionally models only presets; validation and custom-value disclosure remain feature logic.
 */
class PresetChipRow<T : Any>(
    choices: List<PresetChipChoice<T>>,
    selected: T,
    private val style: PresetChipRowStyle = PresetChipRowStyle.WRAP,
) : JBPanel<PresetChipRow<T>>(
    when (style) {
        PresetChipRowStyle.WRAP -> WrappingFlowLayout(FlowLayout.LEADING, AdbToolboxTheme.Spacing.s2, 0)
        PresetChipRowStyle.SEGMENTED -> SegmentedLayout()
    },
) {
    private var publishedPreferredHeight = -1

    init {
        require(choices.isNotEmpty()) { "Preset chip row needs at least one choice" }
        require(choices.map { it.value }.distinct().size == choices.size) { "Preset chip values must be unique" }
        require(choices.any { it.value == selected }) { "Selected value must be one of the choices" }
        isOpaque = false
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (width <= 0) return
        val required = layout.preferredLayoutSize(this)
        if (publishedPreferredHeight != required.height) {
            publishedPreferredHeight = required.height
            preferredSize = Dimension(required.width, required.height)
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

    var onSelectionChanged: (T) -> Unit = {}

    /**
     * True while a value the user chose is still being written: the selection then shows the last
     * confirmed value, so a click on that selected chip is a real request (go back) and notifies too.
     */
    var applyPending: Boolean = false

    val chips: List<PresetChip<T>> = choices.map { choice ->
        PresetChip(choice, segmented = style == PresetChipRowStyle.SEGMENTED).also { chip -> add(chip) }
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (style != PresetChipRowStyle.SEGMENTED) return
        val copy = graphics.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val insets = insets
            val x = insets.left
            val y = insets.top
            val w = width - insets.left - insets.right
            val h = height - insets.top - insets.bottom
            val arc = JBUIScale.scale(6) * 2
            copy.color = AdbToolboxTheme.Colors.field
            copy.fillRoundRect(x, y, w, h, arc, arc)
            copy.color = AdbToolboxTheme.Colors.borderStrong
            copy.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
        } finally {
            copy.dispose()
        }
    }

    private val group = ButtonGroup().also { buttonGroup -> chips.forEach(buttonGroup::add) }

    var selectedValue: T = selected
        private set

    init {
        chips.forEachIndexed { index, chip ->
            chip.isSelected = chip.value == selected
            chip.addActionListener {
                if (applyPending && chip.value == selectedValue) onSelectionChanged(chip.value) else choose(chip.value, notify = true)
            }
            bindTraversal(chip, index, KeyEvent.VK_LEFT, -1)
            bindTraversal(chip, index, KeyEvent.VK_RIGHT, 1)
        }
    }

    /** No chip selected (a choice with nothing applied yet, e.g. an emulator's location before a fix). */
    fun clearSelection() {
        cleared = true
        chips.forEach { chip ->
            chip.isSelected = false
            chip.refreshPresentation()
        }
    }

    fun setSelectedValue(value: T) {
        require(chips.any { it.value == value }) { "Selected value must be one of the choices" }
        choose(value, notify = false)
    }

    private var cleared = false

    private fun choose(value: T, notify: Boolean) {
        if (!cleared && value == selectedValue && chips.first { it.value == value }.isSelected) return
        cleared = false
        selectedValue = value
        chips.forEach { chip ->
            chip.isSelected = chip.value == value
            chip.refreshPresentation()
        }
        if (notify) onSelectionChanged(value)
    }

    private fun bindTraversal(chip: PresetChip<T>, index: Int, keyCode: Int, offset: Int) {
        val actionKey = "adbToolbox.presetChip.$keyCode"
        chip.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), actionKey)
        chip.actionMap.put(actionKey, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) {
                val target = (index + offset).coerceIn(0, chips.lastIndex)
                chips[target].requestFocusInWindow()
                choose(chips[target].value, notify = true)
            }
        })
    }
}

/** [PresetChipRowStyle.SEGMENTED]: 22px chips inside a 1px track with 2px padding and gap; default chip weighs 2. */
private class SegmentedLayout : java.awt.LayoutManager {
    private val pad get() = JBUIScale.scale(1) + JBUIScale.scale(2)
    private val gap get() = JBUIScale.scale(2)
    private val chipHeight get() = JBUIScale.scale(22)

    private fun weight(component: Component) = (component as? PresetChip<*>)?.segmentWeight ?: 1

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val minWidth = parent.components.sumOf { it.preferredSize.width } + gap * (parent.componentCount - 1).coerceAtLeast(0)
        return Dimension(insets.left + insets.right + pad * 2 + minWidth, insets.top + insets.bottom + pad * 2 + chipHeight)
    }

    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val components = parent.components
        if (components.isEmpty()) return
        val available = parent.width - insets.left - insets.right - pad * 2 - gap * (components.size - 1)
        val totalWeight = components.sumOf(::weight)
        // Edges come from the cumulative weight, so rounding spreads over all segments.
        var weightBefore = 0
        components.forEachIndexed { index, component ->
            val start = available * weightBefore / totalWeight
            weightBefore += weight(component)
            val end = available * weightBefore / totalWeight
            component.setBounds(insets.left + pad + start + index * gap, insets.top + pad, end - start, chipHeight)
        }
    }

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit
    override fun removeLayoutComponent(comp: Component?) = Unit
}

/** FlowLayout with a preferred height that reflects the rows required by the current viewport. */
internal class WrappingFlowLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = wrappingSize(target)

    override fun minimumLayoutSize(target: Container): Dimension = wrappingSize(target)

    private fun wrappingSize(target: Container): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        val parentWidth = target.parent?.width ?: 0
        val availableWidth = (if (target.width > 0) target.width else parentWidth)
            .minus(insets.left + insets.right + hgap * 2)
            .takeIf { it > 0 }
            ?: Int.MAX_VALUE
        var rowWidth = 0
        var rowHeight = 0
        var widestRow = 0
        var totalHeight = insets.top + insets.bottom + vgap * 2

        target.components.filter(Component::isVisible).forEach { component ->
            val size = component.preferredSize
            val nextWidth = if (rowWidth == 0) size.width else rowWidth + hgap + size.width
            if (nextWidth > availableWidth && rowWidth > 0) {
                widestRow = maxOf(widestRow, rowWidth)
                totalHeight += rowHeight + vgap
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth = nextWidth
                rowHeight = maxOf(rowHeight, size.height)
            }
        }
        widestRow = maxOf(widestRow, rowWidth)
        totalHeight += rowHeight
        Dimension(widestRow + insets.left + insets.right + hgap * 2, totalHeight)
    }
}

class PresetChip<T : Any> internal constructor(
    private val choice: PresetChipChoice<T>,
    private val segmented: Boolean = false,
) : JToggleButton(choice.label) {
    val value: T get() = choice.value
    val kind: PresetChipKind get() = choice.kind
    internal val isDefaultChoice: Boolean get() = choice.isDefault
    internal val segmentWeight: Int get() = choice.segmentWeight

    /**
     * Chips paint their own surface and border, so they keep the plain Basic UI. A theme's button UI
     * (e.g. `DarculaButtonUI` in Android Studio) lays text out with its own wide insets and
     * truncated every label ("0.8…", "Custo…") at the design's compact width.
     */
    override fun updateUI() {
        setUI(javax.swing.plaf.basic.BasicToggleButtonUI())
    }

    init {
        isOpaque = false
        isContentAreaFilled = false
        isFocusPainted = false
        font = AdbToolboxTheme.Typography.body
        margin = Insets(0, JBUIScale.scale(if (segmented) 6 else 9), 0, JBUIScale.scale(if (segmented) 6 else 9))
        val widestFont = baseFont().deriveFont(java.awt.Font.BOLD)
        preferredSize = Dimension(
            getFontMetrics(widestFont).stringWidth(text) + margin.left + margin.right,
            if (segmented) JBUIScale.scale(22) else AdbToolboxTheme.Sizes.iconButton,
        )
        addItemListener { refreshPresentation() }
        getAccessibleContext().accessibleName = choice.label
        refreshPresentation()
    }

    /** Segmented numbers are mono 11 and the default ("Standard") is UI 11, per the prototype. */
    private fun baseFont(): java.awt.Font = when {
        !segmented -> AdbToolboxTheme.Typography.body
        !choice.monospaced -> AdbToolboxTheme.Typography.body.deriveFont(JBUIScale.scale(11f))
        else -> AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(11f))
    }

    private val highlighted: Boolean
        get() = isSelected && !choice.isDefault && choice.kind == PresetChipKind.PRESET

    internal fun refreshPresentation() {
        if (segmented) {
            refreshSegmentedPresentation()
            return
        }
        when {
            isSelected && !choice.isDefault && choice.kind == PresetChipKind.PRESET -> {
                foreground = AdbToolboxTheme.Colors.amber
                background = AdbToolboxTheme.Colors.amberBg
                border = SolidChipBorder(AdbToolboxTheme.Colors.amber)
            }
            isSelected -> {
                foreground = AdbToolboxTheme.Colors.text
                background = AdbToolboxTheme.Colors.panel
                border = borderForKind(AdbToolboxTheme.Colors.borderStrong)
            }
            else -> {
                foreground = AdbToolboxTheme.Colors.textDim
                background = AdbToolboxTheme.Colors.panel
                border = borderForKind(AdbToolboxTheme.Colors.border)
            }
        }
        font = AdbToolboxTheme.Typography.body.deriveFont(
            if (isSelected) java.awt.Font.BOLD else java.awt.Font.PLAIN,
        )
        isContentAreaFilled = false
        repaint()
    }

    private fun refreshSegmentedPresentation() {
        val radius = { AdbToolboxTheme.Radii.field }
        when {
            highlighted -> {
                foreground = AdbToolboxTheme.Colors.amber
                background = AdbToolboxTheme.Colors.amberBg
                border = SolidChipBorder(AdbToolboxTheme.Colors.amber, radius)
            }
            isSelected -> {
                foreground = AdbToolboxTheme.Colors.text
                background = AdbToolboxTheme.Colors.header
                border = SolidChipBorder(AdbToolboxTheme.Colors.borderStrong, radius)
            }
            else -> {
                foreground = AdbToolboxTheme.Colors.textDim
                background = null
                border = JBUI.Borders.empty(1)
            }
        }
        font = baseFont().deriveFont(if (isSelected) java.awt.Font.BOLD else java.awt.Font.PLAIN)
        isContentAreaFilled = false
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val fill = highlighted || (segmented && isSelected)
        if (fill && background != null) {
            val copy = graphics.create() as Graphics2D
            try {
                copy.color = background
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = (if (segmented) AdbToolboxTheme.Radii.field else AdbToolboxTheme.Radii.chip) * 2
                copy.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                copy.dispose()
            }
        }
        super.paintComponent(graphics)
    }

    private fun borderForKind(color: Color) = when (choice.kind) {
        PresetChipKind.PRESET -> SolidChipBorder(color)
        PresetChipKind.CUSTOM -> DashedChipBorder(color)
    }
}

/** The supplied 20px Logcat severity-filter chip; grouping/filter semantics stay in Logcat UI. */
class LevelChip(val level: LogSeverity) : JToggleButton(level.symbol) {
    init {
        require(level != LogSeverity.ASSERT) { "The supplied minimum-level row contains V, D, I, W and E" }
        isOpaque = false
        isContentAreaFilled = false
        isFocusPainted = false
        font = AdbToolboxTheme.Typography.mono
        margin = Insets(0, 0, 0, 0)
        preferredSize = Dimension(JBUIScale.scale(20), JBUIScale.scale(20))
        addItemListener { refreshPresentation() }
        getAccessibleContext().accessibleName = "Minimum Logcat level: ${level.symbol}"
        refreshPresentation()
    }

    // `levelChips[].style`: the active chip takes the level's own color, 700 weight, `accentBg` and
    // a 1px `accentBorder`; inactive chips are borderless `textFaint` glyphs with hover-only fill.
    private fun refreshPresentation() {
        foreground = if (isSelected) severityColor(level) else AdbToolboxTheme.Colors.textFaint
        font = AdbToolboxTheme.Typography.mono.deriveFont(if (isSelected) java.awt.Font.BOLD else java.awt.Font.PLAIN)
        background = if (isSelected) AdbToolboxTheme.Colors.accentBg else AdbToolboxTheme.Colors.panel
        border = if (isSelected) {
            SolidChipBorder(AdbToolboxTheme.Colors.accentBorder, radius = { AdbToolboxTheme.Radii.field })
        } else {
            javax.swing.BorderFactory.createEmptyBorder()
        }
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        if (isSelected) {
            val copy = graphics.create() as Graphics2D
            try {
                copy.color = background
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = AdbToolboxTheme.Radii.field * 2
                copy.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                copy.dispose()
            }
        }
        super.paintComponent(graphics)
    }
}

private val LogSeverity.symbol: String
    get() = when (this) {
        LogSeverity.VERBOSE -> "V"
        LogSeverity.DEBUG -> "D"
        LogSeverity.INFO -> "I"
        LogSeverity.WARN -> "W"
        LogSeverity.ERROR -> "E"
        LogSeverity.ASSERT -> "A"
    }

private fun severityColor(level: LogSeverity) = when (level) {
    LogSeverity.VERBOSE -> AdbToolboxTheme.LogSeverityColors.verbose.level
    LogSeverity.DEBUG -> AdbToolboxTheme.LogSeverityColors.debug.level
    LogSeverity.INFO -> AdbToolboxTheme.LogSeverityColors.info.level
    LogSeverity.WARN -> AdbToolboxTheme.LogSeverityColors.warn.level
    LogSeverity.ERROR -> AdbToolboxTheme.LogSeverityColors.error.level
    LogSeverity.ASSERT -> AdbToolboxTheme.LogSeverityColors.assert.level
}

internal open class SolidChipBorder(
    private val color: Color,
    private val radius: () -> Int = { AdbToolboxTheme.Radii.chip },
) : AbstractBorder() {
    override fun getBorderInsets(component: Component): Insets = Insets(
        JBUIScale.scale(1),
        JBUIScale.scale(1),
        JBUIScale.scale(1),
        JBUIScale.scale(1),
    )

    override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val copy = graphics.create() as Graphics2D
        try {
            copy.color = color
            val strokeWidth = JBUIScale.scale(1).toFloat()
            val offset = strokeWidth / 2f
            val arc = (radius() * 2).toFloat()
            copy.stroke = borderStroke(strokeWidth)
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.draw(
                RoundRectangle2D.Float(
                    x + offset,
                    y + offset,
                    width - strokeWidth,
                    height - strokeWidth,
                    arc,
                    arc,
                ),
            )
        } finally {
            copy.dispose()
        }
    }

    protected open fun borderStroke(width: Float): BasicStroke = BasicStroke(width)
}

internal class DashedChipBorder(color: Color) : SolidChipBorder(color) {
    override fun borderStroke(width: Float): BasicStroke = BasicStroke(
        width,
        BasicStroke.CAP_BUTT,
        BasicStroke.JOIN_ROUND,
        1f,
        floatArrayOf(JBUIScale.scale(3).toFloat(), JBUIScale.scale(2).toFloat()),
        0f,
    )
}
