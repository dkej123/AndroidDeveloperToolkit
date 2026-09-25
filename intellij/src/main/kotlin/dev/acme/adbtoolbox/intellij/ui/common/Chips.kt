package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.ui.components.JBPanel
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
)

enum class PresetChipKind { PRESET, CUSTOM }

/**
 * The compact, wrapping single-choice row supplied by the design-system prototype section 06.
 * It intentionally models only presets; validation and custom-value disclosure remain feature logic.
 */
class PresetChipRow<T : Any>(
    choices: List<PresetChipChoice<T>>,
    selected: T,
) : JBPanel<PresetChipRow<T>>(
    WrappingFlowLayout(FlowLayout.LEADING, AdbToolboxTheme.Spacing.s2, 0),
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
            var ancestor = parent
            while (ancestor != null) {
                ancestor.invalidate()
                ancestor = ancestor.parent
            }
            parent?.revalidate()
        }
    }

    var onSelectionChanged: (T) -> Unit = {}

    val chips: List<PresetChip<T>> = choices.map { choice ->
        PresetChip(choice).also { chip -> add(chip) }
    }

    private val group = ButtonGroup().also { buttonGroup -> chips.forEach(buttonGroup::add) }

    var selectedValue: T = selected
        private set

    init {
        chips.forEachIndexed { index, chip ->
            chip.isSelected = chip.value == selected
            chip.addActionListener {
                choose(chip.value, notify = true)
            }
            bindTraversal(chip, index, KeyEvent.VK_LEFT, -1)
            bindTraversal(chip, index, KeyEvent.VK_RIGHT, 1)
        }
    }

    fun setSelectedValue(value: T) {
        require(chips.any { it.value == value }) { "Selected value must be one of the choices" }
        choose(value, notify = false)
    }

    private fun choose(value: T, notify: Boolean) {
        if (value == selectedValue && chips.first { it.value == value }.isSelected) return
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

/** FlowLayout with a preferred height that reflects the rows required by the current viewport. */
private class WrappingFlowLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {
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
) : JToggleButton(choice.label) {
    val value: T get() = choice.value
    val kind: PresetChipKind get() = choice.kind

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
        margin = Insets(0, JBUI.scale(9), 0, JBUI.scale(9))
        val widestFont = AdbToolboxTheme.Typography.body.deriveFont(java.awt.Font.BOLD)
        preferredSize = Dimension(
            getFontMetrics(widestFont).stringWidth(text) + margin.left + margin.right,
            AdbToolboxTheme.Sizes.iconButton,
        )
        addItemListener { refreshPresentation() }
        getAccessibleContext().accessibleName = choice.label
        refreshPresentation()
    }

    internal fun refreshPresentation() {
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

    override fun paintComponent(graphics: Graphics) {
        if (isSelected && !choice.isDefault && choice.kind == PresetChipKind.PRESET) {
            val copy = graphics.create() as Graphics2D
            try {
                copy.color = background
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = AdbToolboxTheme.Radii.chip * 2
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
        preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
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
        JBUI.scale(1),
        JBUI.scale(1),
        JBUI.scale(1),
        JBUI.scale(1),
    )

    override fun paintBorder(component: Component, graphics: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val copy = graphics.create() as Graphics2D
        try {
            copy.color = color
            val strokeWidth = JBUI.scale(1).toFloat()
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
        floatArrayOf(JBUI.scale(3).toFloat(), JBUI.scale(2).toFloat()),
        0f,
    )
}
