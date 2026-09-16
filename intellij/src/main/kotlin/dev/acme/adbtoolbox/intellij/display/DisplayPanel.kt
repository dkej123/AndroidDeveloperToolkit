package dev.acme.adbtoolbox.intellij.display

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.display.density.DensityPresets
import dev.acme.adbtoolbox.domain.display.density.percentToDpi
import dev.acme.adbtoolbox.domain.display.fontscale.FontScalePresets
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleRange
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.domain.display.fontscale.formatFontScale
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipChoice
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipKind
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipRow
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.ToggleSwitch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton

/**
 * Task 046's final visual design for the Display view (`design/README.md` §5): Font scale/Display
 * scale sections (chip row + dashed "Custom…" disclosure + override note/reset link) built on the
 * same [PresetChipRow] the Design System already supplies, and a Quick toggles section built on
 * [ToggleSwitch]. Every header meta value and toggle value text is rendered only from a device
 * readback ([FontScaleState]/[DensityViewState]/[QuickTogglesViewState]) — never from the value a
 * control was last clicked with — so the view can never visually claim an unconfirmed value.
 */
class DisplayPanel(
    private val onApplyFontScale: (Double) -> Unit,
    private val onResetFontScale: () -> Unit,
    private val onApplyDensityPreset: (Int) -> Unit,
    private val onApplyCustomDensity: (Int) -> Unit,
    private val onResetDensity: () -> Unit,
    onSetDarkTheme: (Boolean) -> Unit,
    onSetShowTouches: (Boolean) -> Unit,
    onSetAnimationsOff: (Boolean) -> Unit,
) : JBPanel<DisplayPanel>(BorderLayout()) {

    private sealed interface FontChoice {
        data class Preset(val value: Double) : FontChoice
        data object Custom : FontChoice
    }

    private sealed interface DensityChoice {
        data class Preset(val percent: Int) : DensityChoice
        data object Custom : DensityChoice
    }

    // ---- Font scale section (`design/README.md` §5.1) ----

    private val fontTitleLabel = sectionTitleLabel("Font scale")
    private val fontMetaLabel = sectionMetaLabel()
    private val fontHeader = sectionHeader(fontTitleLabel, fontMetaLabel)

    private val fontChipRow: PresetChipRow<FontChoice> = PresetChipRow(
        choices = FontScalePresets.VALUES.map { value ->
            PresetChipChoice<FontChoice>(
                value = FontChoice.Preset(value),
                label = fontChipLabel(value),
                isDefault = value == FontScalePresets.DEFAULT,
            )
        } + PresetChipChoice(
            value = FontChoice.Custom,
            label = "Custom…",
            kind = PresetChipKind.CUSTOM,
        ),
        selected = FontChoice.Preset(FontScalePresets.DEFAULT),
    ).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s5)
        chips.forEach { chip ->
            chip.toolTipText = when (val value = chip.value) {
                is FontChoice.Preset -> if (value.value == FontScalePresets.DEFAULT) {
                    "Device default"
                } else {
                    "settings put system font_scale ${formatFontScale(value.value)}"
                }
                FontChoice.Custom -> "Enter any scale between ${formatFontScale(FontScaleRange.MIN)} and ${formatFontScale(FontScaleRange.MAX)}"
            }
        }
        onSelectionChanged = { choice ->
            when (choice) {
                is FontChoice.Preset -> {
                    fontCustomRow.isVisible = false
                    onApplyFontScale(choice.value)
                }
                FontChoice.Custom -> fontCustomRow.isVisible = true
            }
        }
    }

    private val fontCustomField = customField()
    private val fontCustomErrorLabel = errorLabel()
    private val fontCustomRow = customDisclosureRow(
        field = fontCustomField,
        unit = "×",
        errorLabel = fontCustomErrorLabel,
        onApply = ::applyFontCustomFromField,
    )

    private val fontOverrideNoteLabel = overrideNoteLabel("Overriding device default (${fontChipLabel(FontScalePresets.DEFAULT)})")
    private val fontResetLink = linkButton("Reset") { onResetFontScale() }
    private val fontOverrideRow = overrideRow(fontOverrideNoteLabel, fontResetLink)

    private val fontSection = section(fontHeader, fontChipRow, fontCustomRow, fontOverrideRow)

    // ---- Display scale (density) section (`design/README.md` §5.2) ----

    private val densityTitleLabel = sectionTitleLabel("Display scale")
    private val densityMetaLabel = sectionMetaLabel()
    private val densityHeader = sectionHeader(densityTitleLabel, densityMetaLabel)

    private val densityChipRow: PresetChipRow<DensityChoice> = PresetChipRow(
        choices = DensityPresets.PERCENTAGES.map { percent ->
            PresetChipChoice<DensityChoice>(
                value = DensityChoice.Preset(percent),
                label = "$percent%",
                isDefault = percent == 100,
            )
        } + PresetChipChoice(
            value = DensityChoice.Custom,
            label = "Custom…",
            kind = PresetChipKind.CUSTOM,
        ),
        selected = DensityChoice.Preset(100),
    ).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s5)
        onSelectionChanged = { choice ->
            when (choice) {
                is DensityChoice.Preset -> {
                    densityCustomRow.isVisible = false
                    onApplyDensityPreset(choice.percent)
                }
                DensityChoice.Custom -> densityCustomRow.isVisible = true
            }
        }
    }

    private val densityCustomField = customField()
    private val densityCustomErrorLabel = errorLabel()
    private val densityCustomRow = customDisclosureRow(
        field = densityCustomField,
        unit = "dpi",
        errorLabel = densityCustomErrorLabel,
        onApply = ::applyDensityCustomFromField,
    )

    private val densityHelpLabel = helpLabel("")

    private val densityOverrideNoteLabel = overrideNoteLabel("")
    private val densityResetLink = linkButton("Reset to physical") { onResetDensity() }
    private val densityOverrideRow = overrideRow(densityOverrideNoteLabel, densityResetLink)

    private val densitySection = section(densityHeader, densityChipRow, densityCustomRow, densityHelpLabel, densityOverrideRow)

    // ---- Quick toggles section (`design/README.md` §5.3) ----

    private val darkThemeToggle = ToggleSwitch().apply {
        toolTipText = "cmd uimode night yes|no"
        addActionListener { onSetDarkTheme(isSelected) }
    }
    private val darkThemeValueLabel = toggleValueLabel()
    private val darkThemeRow = toggleRow(darkThemeToggle, "Dark theme", darkThemeValueLabel)

    private val showTouchesToggle = ToggleSwitch().apply {
        toolTipText = "Useful while recording"
        addActionListener { onSetShowTouches(isSelected) }
    }
    private val showTouchesValueLabel = toggleValueLabel()
    private val showTouchesRow = toggleRow(showTouchesToggle, "Show touches", showTouchesValueLabel)

    private val animationsOffToggle = ToggleSwitch().apply {
        toolTipText = "Sets window, transition and animator scales to 0"
        addActionListener { onSetAnimationsOff(isSelected) }
    }
    private val animationsValueLabel = toggleValueLabel()
    private val animationsRow = toggleRow(animationsOffToggle, "Animations off", animationsValueLabel)

    private val togglesSection = section(
        sectionHeader(sectionTitleLabel("Quick toggles"), null),
        darkThemeRow,
        animationsRow,
        showTouchesRow,
    ).apply {
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s5, 0)
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.bg
        add(fontSection)
        add(densitySection)
        add(togglesSection)
    }

    private val scrollPane = JScrollPane(contentPanel).apply {
        border = BorderFactory.createEmptyBorder()
        verticalScrollBar.unitIncrement = AdbToolboxTheme.Spacing.s5
    }

    init {
        background = AdbToolboxTheme.Colors.bg
        add(scrollPane, BorderLayout.CENTER)
    }

    // ---- Test-only visibility hooks ----
    internal val fontChipRowForTest: PresetChipRow<*> get() = fontChipRow
    internal val fontCustomFieldForTest: JTextField get() = fontCustomField
    internal val fontCustomRowForTest: JPanel get() = fontCustomRow
    internal val fontCustomErrorLabelForTest: JBLabel get() = fontCustomErrorLabel
    internal val fontResetButtonForTest: JButton get() = fontResetLink
    internal val fontOverrideRowForTest: JPanel get() = fontOverrideRow
    internal val fontMetaLabelForTest: JBLabel get() = fontMetaLabel

    internal val densityChipRowForTest: PresetChipRow<*> get() = densityChipRow
    internal val densityCustomFieldForTest: JTextField get() = densityCustomField
    internal val densityCustomRowForTest: JPanel get() = densityCustomRow
    internal val densityResetButtonForTest: JButton get() = densityResetLink
    internal val densityOverrideRowForTest: JPanel get() = densityOverrideRow
    internal val densityMetaLabelForTest: JBLabel get() = densityMetaLabel
    internal val densityHelpLabelForTest: JBLabel get() = densityHelpLabel

    internal val darkThemeToggleForTest: JToggleButton get() = darkThemeToggle
    internal val showTouchesToggleForTest: JToggleButton get() = showTouchesToggle
    internal val animationsOffToggleForTest: JToggleButton get() = animationsOffToggle
    internal val darkThemeValueLabelForTest: JBLabel get() = darkThemeValueLabel
    internal val showTouchesValueLabelForTest: JBLabel get() = showTouchesValueLabel
    internal val animationsValueLabelForTest: JBLabel get() = animationsValueLabel

    fun update(state: FontScaleState) {
        val current = when (state) {
            FontScaleState.Loading -> null
            is FontScaleState.Idle -> state.current
            is FontScaleState.Applying -> state.current
            is FontScaleState.Error -> state.current
        }
        val overridden = current != null && current != FontScalePresets.DEFAULT
        fontMetaLabel.text = when {
            current == null -> "—"
            overridden -> "${formatFontScale(current)}× applied"
            else -> "default"
        }
        fontMetaLabel.foreground = if (overridden) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint
        if (current != null) {
            val selection = if (current in FontScalePresets.VALUES) FontChoice.Preset(current) else FontChoice.Custom
            fontChipRow.setSelectedValue(selection)
        }
        fontOverrideRow.isVisible = overridden
        if (state is FontScaleState.Error) {
            fontCustomErrorLabel.text = state.message
            fontCustomErrorLabel.isVisible = true
            fontCustomRow.isVisible = true
        }
    }

    fun update(state: DensityViewState) {
        val reading = when (state) {
            DensityViewState.Loading -> null
            is DensityViewState.Idle -> state.reading
            is DensityViewState.Applying -> state.reading
            is DensityViewState.Error -> state.reading
        }
        val overridden = reading?.overrideDpi != null
        densityMetaLabel.text = when {
            reading == null -> "—"
            overridden -> "${reading.overrideDpi} dpi"
            else -> "${reading.physicalDpi} dpi"
        }
        densityMetaLabel.foreground = if (overridden) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint
        if (reading != null) {
            densityChipRow.chips.forEach { chip ->
                val value = chip.value
                chip.toolTipText = when (value) {
                    is DensityChoice.Preset -> if (value.percent == 100) {
                        "Physical density — ${reading.physicalDpi} dpi"
                    } else {
                        "${percentToDpi(reading.physicalDpi, value.percent)} dpi"
                    }
                    DensityChoice.Custom -> "Enter an absolute dpi value"
                }
            }
            densityHelpLabel.text =
                "Percentages are relative to the physical density (${reading.physicalDpi} dpi). " +
                    "Values outside ${DensityPresets.SAFE_RANGE_MIN_PERCENT}–${DensityPresets.SAFE_RANGE_MAX_PERCENT}% can make the UI unusable."
            densityOverrideNoteLabel.text = "Physical density is ${reading.physicalDpi} dpi"
            val selection = if (reading.overrideDpi == null) {
                DensityChoice.Preset(100)
            } else {
                DensityPresets.PERCENTAGES
                    .firstOrNull { percentToDpi(reading.physicalDpi, it) == reading.overrideDpi }
                    ?.let { DensityChoice.Preset(it) }
                    ?: DensityChoice.Custom
            }
            densityChipRow.setSelectedValue(selection)
        }
        densityOverrideRow.isVisible = overridden
        if (state is DensityViewState.Error) {
            densityCustomErrorLabel.text = state.message
            densityCustomErrorLabel.isVisible = true
            densityCustomRow.isVisible = true
        }
    }

    fun update(state: QuickTogglesViewState) {
        applyToggleState(darkThemeToggle, darkThemeValueLabel, state.darkTheme, toText = { if (it) "yes" else "no" })
        applyToggleState(showTouchesToggle, showTouchesValueLabel, state.showTouches, toText = { if (it) "on" else "off" })
        applyToggleState(
            animationsOffToggle,
            animationsValueLabel,
            state.animations,
            toSelected = { it != AnimationsSummary.AllOn },
            toText = { summary ->
                when (summary) {
                    AnimationsSummary.AllOn -> "1×"
                    AnimationsSummary.AllOff -> "0×"
                    is AnimationsSummary.Mixed -> "mixed"
                }
            },
        )
    }

    private fun <T> applyToggleState(
        toggle: JToggleButton,
        valueLabel: JBLabel,
        field: QuickToggleFieldState<T>,
        toSelected: (T) -> Boolean = { it as Boolean },
        toText: (T) -> String = { "" },
    ) {
        when (field) {
            QuickToggleFieldState.Loading -> toggle.isEnabled = false
            is QuickToggleFieldState.Idle -> {
                toggle.isEnabled = true
                toggle.isSelected = toSelected(field.value)
            }
            is QuickToggleFieldState.Applying -> {
                toggle.isEnabled = false
                toggle.isSelected = toSelected(field.optimistic)
            }
            is QuickToggleFieldState.Error -> {
                toggle.isEnabled = true
                field.lastKnown?.let { toggle.isSelected = toSelected(it) }
            }
        }
        valueLabel.text = valueText(field, toText)
    }

    private fun <T> valueText(field: QuickToggleFieldState<T>, toText: (T) -> String): String = when (field) {
        QuickToggleFieldState.Loading -> "—"
        is QuickToggleFieldState.Idle -> toText(field.value)
        is QuickToggleFieldState.Applying -> toText(field.optimistic)
        is QuickToggleFieldState.Error -> field.lastKnown?.let(toText) ?: "—"
    }

    private fun applyFontCustomFromField() {
        val parsed = fontCustomField.text.trim().toDoubleOrNull()
        if (parsed == null) {
            fontCustomErrorLabel.text = "Invalid value"
            fontCustomErrorLabel.isVisible = true
        } else {
            fontCustomErrorLabel.isVisible = false
            onApplyFontScale(parsed)
        }
    }

    private fun applyDensityCustomFromField() {
        val parsed = densityCustomField.text.trim().toIntOrNull()
        if (parsed == null) {
            densityCustomErrorLabel.text = "Invalid value"
            densityCustomErrorLabel.isVisible = true
        } else {
            densityCustomErrorLabel.isVisible = false
            onApplyCustomDensity(parsed)
        }
    }

    private companion object {
        fun sectionTitleLabel(text: String) = JBLabel(text).apply {
            font = AdbToolboxTheme.Typography.sectionTitle
        }

        fun sectionMetaLabel() = JBLabel("—").apply {
            font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
            foreground = AdbToolboxTheme.Colors.textFaint
        }

        fun sectionHeader(title: JBLabel, meta: JBLabel?) = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s5)
            add(title, BorderLayout.WEST)
            if (meta != null) add(meta, BorderLayout.EAST)
        }

        fun customField() = JBTextField().apply {
            font = AdbToolboxTheme.Typography.mono
            preferredSize = Dimension(JBUI.scale(64), AdbToolboxTheme.Sizes.field)
            border = BorderFactory.createCompoundBorder(
                SolidChipBorder(AdbToolboxTheme.Colors.borderStrong),
                JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s3),
            )
        }

        fun errorLabel() = JBLabel("").apply {
            font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(10.5f))
            foreground = AdbToolboxTheme.Colors.red
            isVisible = false
        }

        fun customDisclosureRow(field: JBTextField, unit: String, errorLabel: JBLabel, onApply: () -> Unit): JPanel {
            field.addActionListener { onApply() }
            val unitLabel = JBLabel(unit).apply {
                font = AdbToolboxTheme.Typography.mono
                foreground = AdbToolboxTheme.Colors.textFaint
            }
            val applyButton = JButton("Apply").apply {
                preferredSize = Dimension(preferredSize.width, AdbToolboxTheme.Sizes.secondaryButton)
                foreground = AdbToolboxTheme.Colors.text
                isContentAreaFilled = false
                isFocusPainted = false
                border = SolidChipBorder(AdbToolboxTheme.Colors.borderStrong)
                font = AdbToolboxTheme.Typography.body
                addActionListener { onApply() }
            }
            return JPanel(FlowLayout(FlowLayout.LEADING, AdbToolboxTheme.Spacing.s3, 0)).apply {
                isOpaque = false
                isVisible = false
                border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.s5, 0, AdbToolboxTheme.Spacing.s5)
                add(field)
                add(unitLabel)
                add(applyButton)
                add(errorLabel)
            }
        }

        fun overrideNoteLabel(text: String) = JBLabel(text).apply {
            font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(10.5f))
            foreground = AdbToolboxTheme.Colors.textFaint
        }

        fun linkButton(text: String, onClick: () -> Unit) = JButton(text).apply {
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
            foreground = AdbToolboxTheme.Colors.accent
            font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11f))
            addActionListener { onClick() }
        }

        fun overrideRow(note: JBLabel, link: JButton) = JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = false
            border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.s5, 0, AdbToolboxTheme.Spacing.s5)
            add(note, BorderLayout.WEST)
            add(link, BorderLayout.EAST)
        }

        fun helpLabel(text: String) = JBLabel(text).apply {
            font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(10.5f))
            foreground = AdbToolboxTheme.Colors.textFaint
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s5, AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.s5)
        }

        fun section(vararg children: java.awt.Component) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border),
                JBUI.Borders.empty(AdbToolboxTheme.Spacing.s5, 0, AdbToolboxTheme.Spacing.s5, 0),
            )
            children.forEachIndexed { index, child ->
                if (index > 0) add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3))
                add(child)
            }
        }

        fun toggleValueLabel() = JBLabel("").apply {
            font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
            foreground = AdbToolboxTheme.Colors.textFaint
        }

        fun toggleRow(toggle: ToggleSwitch, label: String, valueLabel: JBLabel): JPanel {
            val labelComponent = JBLabel(label).apply {
                font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(11.5f))
            }
            val leading = JPanel(FlowLayout(FlowLayout.LEADING, AdbToolboxTheme.Spacing.s4, 0)).apply {
                isOpaque = false
                add(toggle)
                add(labelComponent)
            }
            return JPanel(BorderLayout()).apply {
                isOpaque = false
                preferredSize = Dimension(preferredSize.width, AdbToolboxTheme.Sizes.toggleRow)
                border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s5)
                add(leading, BorderLayout.WEST)
                add(valueLabel, BorderLayout.EAST)
            }
        }

        fun fontChipLabel(value: Double): String {
            val text = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            return "$text×"
        }
    }
}
