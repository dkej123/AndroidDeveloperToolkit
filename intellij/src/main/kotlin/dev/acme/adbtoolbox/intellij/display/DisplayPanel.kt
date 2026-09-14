package dev.acme.adbtoolbox.intellij.display

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.display.density.DensityPresets
import dev.acme.adbtoolbox.domain.display.fontscale.FontScalePresets
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.domain.display.fontscale.formatFontScale
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JToggleButton

/**
 * Task 029's minimal, structural Display view: font-scale/density preset chips and custom fields,
 * quick toggles, and per-section status/error labels — purely structural Swing controls (no color,
 * icon, spacing, or final layout), matching [dev.acme.adbtoolbox.intellij.apps.AppsPanel]'s
 * "final design styling/layout is out of scope" convention. A custom field's [JTextField] built-in
 * Enter-key `actionListener` firing is this task's "Enter applies a custom value" keyboard binding
 * (`design/README.md` Interactions) — no extra key-binding code is needed for it.
 */
class DisplayPanel(
    onApplyFontScale: (Double) -> Unit,
    onResetFontScale: () -> Unit,
    onApplyDensityPreset: (Int) -> Unit,
    onApplyCustomDensity: (Int) -> Unit,
    onResetDensity: () -> Unit,
    onSetDarkTheme: (Boolean) -> Unit,
    onSetShowTouches: (Boolean) -> Unit,
    onSetAnimationsOff: (Boolean) -> Unit,
) : JBPanel<DisplayPanel>(GridLayout(0, 1)) {

    private val fontScaleStatusLabel = JBLabel("")
    private val fontScalePresetButtons = FontScalePresets.VALUES.map { value ->
        JButton(formatFontScale(value)).apply { addActionListener { onApplyFontScale(value) } }
    }
    private val fontScaleCustomField = JBTextField().apply {
        addActionListener {
            text.trim().toDoubleOrNull()?.let(onApplyFontScale) ?: run { fontScaleStatusLabel.text = "Invalid value" }
        }
    }
    private val fontScaleResetButton = JButton("Reset").apply { addActionListener { onResetFontScale() } }
    private val fontScaleRow = JPanel().apply {
        fontScalePresetButtons.forEach(::add)
        add(fontScaleCustomField)
        add(fontScaleResetButton)
        add(fontScaleStatusLabel)
    }

    private val densityStatusLabel = JBLabel("")
    private val densityPresetButtons = DensityPresets.PERCENTAGES.map { percent ->
        JButton("$percent%").apply { addActionListener { onApplyDensityPreset(percent) } }
    }
    private val densityCustomField = JBTextField().apply {
        addActionListener {
            text.trim().toIntOrNull()?.let(onApplyCustomDensity) ?: run { densityStatusLabel.text = "Invalid value" }
        }
    }
    private val densityResetButton = JButton("Reset to physical").apply { addActionListener { onResetDensity() } }
    private val densityRow = JPanel().apply {
        densityPresetButtons.forEach(::add)
        add(densityCustomField)
        add(densityResetButton)
        add(densityStatusLabel)
    }

    private val darkThemeToggle = JToggleButton("Dark theme").apply {
        addActionListener { onSetDarkTheme(isSelected) }
    }
    private val showTouchesToggle = JToggleButton("Show touches").apply {
        addActionListener { onSetShowTouches(isSelected) }
    }
    private val animationsOffToggle = JToggleButton("Animations off").apply {
        addActionListener { onSetAnimationsOff(isSelected) }
    }
    private val quickTogglesRow = JPanel().apply {
        add(darkThemeToggle)
        add(showTouchesToggle)
        add(animationsOffToggle)
    }

    init {
        add(fontScaleRow)
        add(densityRow)
        add(quickTogglesRow)
    }

    internal val fontScaleCustomFieldForTest: JTextField get() = fontScaleCustomField
    internal val fontScaleResetButtonForTest: JButton get() = fontScaleResetButton
    internal val fontScaleStatusLabelForTest: JBLabel get() = fontScaleStatusLabel
    internal val densityCustomFieldForTest: JTextField get() = densityCustomField
    internal val densityResetButtonForTest: JButton get() = densityResetButton
    internal val densityStatusLabelForTest: JBLabel get() = densityStatusLabel
    internal val darkThemeToggleForTest: JToggleButton get() = darkThemeToggle
    internal val showTouchesToggleForTest: JToggleButton get() = showTouchesToggle
    internal val animationsOffToggleForTest: JToggleButton get() = animationsOffToggle

    fun update(state: FontScaleState) {
        fontScaleStatusLabel.text = when (state) {
            FontScaleState.Loading -> "Loading…"
            is FontScaleState.Idle -> if (state.current == FontScalePresets.DEFAULT) {
                "default"
            } else {
                "${formatFontScale(state.current)}× applied"
            }
            is FontScaleState.Applying -> "Applying…"
            is FontScaleState.Error -> state.message
        }
    }

    fun update(state: DensityViewState) {
        densityStatusLabel.text = when (state) {
            DensityViewState.Loading -> "Loading…"
            is DensityViewState.Idle -> if (state.reading.overrideDpi == null) {
                "${state.reading.physicalDpi} dpi"
            } else {
                "${state.reading.overrideDpi} dpi"
            }
            is DensityViewState.Applying -> "Applying…"
            is DensityViewState.Error -> state.message
        }
    }

    fun update(state: QuickTogglesViewState) {
        applyToggleState(darkThemeToggle, state.darkTheme)
        applyToggleState(showTouchesToggle, state.showTouches)
        applyToggleState(animationsOffToggle, state.animations) { it != AnimationsSummary.AllOn }
    }

    private fun <T> applyToggleState(
        toggle: JToggleButton,
        field: QuickToggleFieldState<T>,
        toSelected: (T) -> Boolean = { it as Boolean },
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
    }
}
