package dev.acme.adbtoolbox.intellij.display

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import java.awt.event.ActionEvent

/**
 * Task 046's final visual design for the Display view (`design/README.md` §5): font-scale/density
 * preset chip rows built on [dev.acme.adbtoolbox.intellij.ui.common.PresetChipRow], dashed "Custom…"
 * disclosures, override note/reset-link rows, and a Quick toggles section built on
 * [dev.acme.adbtoolbox.intellij.ui.common.ToggleSwitch]. Every rendered value always comes from a
 * device readback ([FontScaleState]/[DensityViewState]/[QuickTogglesViewState]) — the acceptance
 * criterion that the view must never visually claim an unconfirmed value.
 */
class DisplayPanelTest : BasePlatformTestCase() {

    private fun panel(
        onApplyFontScale: (Double) -> Unit = {},
        onResetFontScale: () -> Unit = {},
        onApplyDensityPreset: (Int) -> Unit = {},
        onApplyCustomDensity: (Int) -> Unit = {},
        onResetDensity: () -> Unit = {},
        onSetDarkTheme: (Boolean) -> Unit = {},
        onSetShowTouches: (Boolean) -> Unit = {},
        onSetAnimationsOff: (Boolean) -> Unit = {},
    ) = DisplayPanel(
        onApplyFontScale, onResetFontScale, onApplyDensityPreset, onApplyCustomDensity, onResetDensity,
        onSetDarkTheme, onSetShowTouches, onSetAnimationsOff,
    )

    // ---- Font scale ----

    fun `test clicking a font-scale preset chip invokes onApplyFontScale with its value`() {
        var applied: Double? = null
        val p = panel(onApplyFontScale = { applied = it })

        p.fontChipRowForTest.chips.first { it.label == "1.3×" }.doClick()

        assertEquals(1.3, applied)
    }

    fun `test clicking the font-scale Custom chip reveals the custom disclosure row without applying`() {
        var invoked = false
        val p = panel(onApplyFontScale = { invoked = true })

        p.fontChipRowForTest.chips.first { it.label == "Custom…" }.doClick()

        assertTrue(p.fontCustomRowForTest.isVisible)
        assertFalse(invoked)
    }

    fun `test every font-scale chip remains inside the row at dock width`() {
        val p = panel()
        p.setSize(346, 620)
        recursivelyLayout(p)
        recursivelyLayout(p)

        val row = p.fontChipRowForTest
        row.chips.forEach { chip ->
            assertTrue("${chip.text} exceeds row width", chip.x + chip.width <= row.width)
            assertTrue("${chip.text} exceeds row height", chip.y + chip.height <= row.height)
        }
    }

    fun `test pressing Enter in the font-scale custom field applies the parsed value`() {
        var applied: Double? = null
        val p = panel(onApplyFontScale = { applied = it })

        p.fontCustomFieldForTest.text = "1.3"
        p.fontCustomFieldForTest.postActionEvent()

        assertEquals(1.3, applied)
    }

    fun `test an unparsable custom font-scale value is rejected locally without invoking the callback`() {
        var invoked = false
        val p = panel(onApplyFontScale = { invoked = true })

        p.fontCustomFieldForTest.text = "abc"
        p.fontCustomFieldForTest.postActionEvent()

        assertFalse(invoked)
        assertEquals("Invalid value", p.fontCustomErrorLabelForTest.text)
        assertTrue(p.fontCustomErrorLabelForTest.isVisible)
    }

    fun `test clicking the font-scale reset link invokes onResetFontScale`() {
        var resets = 0
        val p = panel(onResetFontScale = { resets++ })

        p.fontResetButtonForTest.doClick()

        assertEquals(1, resets)
    }

    fun `test update with FontScaleState Idle at the default shows default and hides the override row`() {
        val p = panel()

        p.update(FontScaleState.Idle(1.0))

        assertEquals("default", p.fontMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.textFaint, p.fontMetaLabelForTest.foreground)
        assertFalse(p.fontOverrideRowForTest.isVisible)
    }

    fun `test update with FontScaleState Idle overridden shows the applied value in amber and reveals the override row`() {
        val p = panel()

        p.update(FontScaleState.Idle(1.3))

        assertEquals("1.3× applied", p.fontMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.amber, p.fontMetaLabelForTest.foreground)
        assertTrue(p.fontOverrideRowForTest.isVisible)
    }

    fun `test update with FontScaleState Error shows the error message next to the custom field`() {
        val p = panel()

        p.update(FontScaleState.Error(current = 1.0, message = "Value must be between 0.25 and 5.0"))

        assertEquals("Value must be between 0.25 and 5.0", p.fontCustomErrorLabelForTest.text)
        assertTrue(p.fontCustomErrorLabelForTest.isVisible)
        assertTrue(p.fontCustomRowForTest.isVisible)
    }

    // ---- Display scale (density) ----

    fun `test clicking a density preset chip invokes onApplyDensityPreset with its percent`() {
        var applied: Int? = null
        val p = panel(onApplyDensityPreset = { applied = it })

        p.densityChipRowForTest.chips.first { it.label == "125%" }.doClick()

        assertEquals(125, applied)
    }

    fun `test pressing Enter in the density custom field applies the parsed dpi`() {
        var applied: Int? = null
        val p = panel(onApplyCustomDensity = { applied = it })

        p.densityCustomFieldForTest.text = "480"
        p.densityCustomFieldForTest.postActionEvent()

        assertEquals(480, applied)
    }

    fun `test an unparsable custom density value is rejected locally without invoking the callback`() {
        var invoked = false
        val p = panel(onApplyCustomDensity = { invoked = true })

        p.densityCustomFieldForTest.text = "not a number"
        p.densityCustomFieldForTest.postActionEvent()

        assertFalse(invoked)
    }

    fun `test clicking the density reset-to-physical link invokes onResetDensity`() {
        var resets = 0
        val p = panel(onResetDensity = { resets++ })

        p.densityResetButtonForTest.doClick()

        assertEquals(1, resets)
    }

    fun `test update with DensityViewState Idle at physical shows the physical dpi and hides the override row`() {
        val p = panel()

        p.update(DensityViewState.Idle(DensityReading(420, null)))

        assertEquals("420 dpi", p.densityMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.textFaint, p.densityMetaLabelForTest.foreground)
        assertFalse(p.densityOverrideRowForTest.isVisible)
        assertTrue(p.densityHelpLabelForTest.text.contains("420 dpi"))
    }

    fun `test update with DensityViewState Idle overridden shows the override dpi in amber and reveals the override row`() {
        val p = panel()

        p.update(DensityViewState.Idle(DensityReading(420, 525)))

        assertEquals("525 dpi", p.densityMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.amber, p.densityMetaLabelForTest.foreground)
        assertTrue(p.densityOverrideRowForTest.isVisible)
    }

    // ---- Quick toggles ----

    fun `test toggling dark theme invokes onSetDarkTheme with the new selection`() {
        var enabled: Boolean? = null
        val p = panel(onSetDarkTheme = { enabled = it })

        p.darkThemeToggleForTest.doClick()

        assertEquals(true, enabled)
    }

    fun `test toggling show touches invokes onSetShowTouches with the new selection`() {
        var enabled: Boolean? = null
        val p = panel(onSetShowTouches = { enabled = it })

        p.showTouchesToggleForTest.doClick()

        assertEquals(true, enabled)
    }

    fun `test toggling animations off invokes onSetAnimationsOff with the new selection`() {
        var off: Boolean? = null
        val p = panel(onSetAnimationsOff = { off = it })

        p.animationsOffToggleForTest.doClick()

        assertEquals(true, off)
    }

    fun `test update with QuickTogglesViewState reflects Idle values and mono value text on the toggles`() {
        val p = panel()

        p.update(
            QuickTogglesViewState(
                darkTheme = QuickToggleFieldState.Idle(true),
                showTouches = QuickToggleFieldState.Idle(false),
                animations = QuickToggleFieldState.Idle(AnimationsSummary.AllOff),
            ),
        )

        assertTrue(p.darkThemeToggleForTest.isSelected)
        assertEquals("yes", p.darkThemeValueLabelForTest.text)
        assertFalse(p.showTouchesToggleForTest.isSelected)
        assertEquals("off", p.showTouchesValueLabelForTest.text)
        assertTrue(p.animationsOffToggleForTest.isSelected)
        assertEquals("0×", p.animationsValueLabelForTest.text)
        assertTrue(p.darkThemeToggleForTest.isEnabled)
    }

    fun `test update with QuickTogglesViewState Loading disables the toggles`() {
        val p = panel()

        p.update(QuickTogglesViewState())

        assertFalse(p.darkThemeToggleForTest.isEnabled)
        assertFalse(p.showTouchesToggleForTest.isEnabled)
        assertFalse(p.animationsOffToggleForTest.isEnabled)
    }

    fun `test update with QuickTogglesViewState animations Mixed renders a neutral value without flattening it to a boolean`() {
        val p = panel()

        p.update(
            QuickTogglesViewState(
                darkTheme = QuickToggleFieldState.Idle(false),
                showTouches = QuickToggleFieldState.Idle(false),
                animations = QuickToggleFieldState.Idle(AnimationsSummary.Mixed(0f, 1f, 0.5f)),
            ),
        )

        assertEquals("mixed", p.animationsValueLabelForTest.text)
    }
}

private fun javax.swing.JTextField.postActionEvent() {
    actionListeners.forEach { it.actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "")) }
}

private fun recursivelyLayout(component: java.awt.Component) {
    if (component is java.awt.Container) {
        component.doLayout()
        component.components.forEach(::recursivelyLayout)
    }
}
