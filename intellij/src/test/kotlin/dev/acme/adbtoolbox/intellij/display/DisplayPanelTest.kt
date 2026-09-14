package dev.acme.adbtoolbox.intellij.display

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.display.density.DensityReading
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import java.awt.event.ActionEvent

/**
 * [DisplayPanel] is task 029's minimal, structural Display view: font-scale/density presets/custom
 * fields and quick toggles (`design/README.md` §5) — purely structural, like
 * [dev.acme.adbtoolbox.intellij.apps.AppsPanel]; final visual design is a later task. A text
 * field's `actionListener` firing on Enter (simulated here via [javax.swing.JTextField.postActionEvent])
 * is this task's keyboard-interaction requirement.
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

    fun `test pressing Enter in the font-scale custom field applies the parsed value`() {
        var applied: Double? = null
        val p = panel(onApplyFontScale = { applied = it })

        p.fontScaleCustomFieldForTest.text = "1.3"
        p.fontScaleCustomFieldForTest.postActionEvent()

        assertEquals(1.3, applied)
    }

    fun `test an unparsable custom font-scale value is rejected locally without invoking the callback`() {
        var invoked = false
        val p = panel(onApplyFontScale = { invoked = true })

        p.fontScaleCustomFieldForTest.text = "abc"
        p.fontScaleCustomFieldForTest.postActionEvent()

        assertFalse(invoked)
        assertEquals("Invalid value", p.fontScaleStatusLabelForTest.text)
    }

    fun `test clicking the font-scale reset button invokes onResetFontScale`() {
        var resets = 0
        val p = panel(onResetFontScale = { resets++ })

        p.fontScaleResetButtonForTest.doClick()

        assertEquals(1, resets)
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
        assertEquals("Invalid value", p.densityStatusLabelForTest.text)
    }

    fun `test clicking the density reset button invokes onResetDensity`() {
        var resets = 0
        val p = panel(onResetDensity = { resets++ })

        p.densityResetButtonForTest.doClick()

        assertEquals(1, resets)
    }

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

    fun `test update with FontScaleState Idle at the default shows default`() {
        val p = panel()

        p.update(FontScaleState.Idle(1.0))

        assertEquals("default", p.fontScaleStatusLabelForTest.text)
    }

    fun `test update with FontScaleState Idle overridden shows the applied value`() {
        val p = panel()

        p.update(FontScaleState.Idle(1.3))

        assertEquals("1.3× applied", p.fontScaleStatusLabelForTest.text)
    }

    fun `test update with FontScaleState Error shows the error message`() {
        val p = panel()

        p.update(FontScaleState.Error(current = 1.0, message = "device offline"))

        assertEquals("device offline", p.fontScaleStatusLabelForTest.text)
    }

    fun `test update with DensityViewState Idle at physical shows the physical dpi`() {
        val p = panel()

        p.update(DensityViewState.Idle(DensityReading(420, null)))

        assertEquals("420 dpi", p.densityStatusLabelForTest.text)
    }

    fun `test update with DensityViewState Idle overridden shows the override dpi`() {
        val p = panel()

        p.update(DensityViewState.Idle(DensityReading(420, 525)))

        assertEquals("525 dpi", p.densityStatusLabelForTest.text)
    }

    fun `test update with QuickTogglesViewState reflects Idle values on the toggles`() {
        val p = panel()

        p.update(
            QuickTogglesViewState(
                darkTheme = QuickToggleFieldState.Idle(true),
                showTouches = QuickToggleFieldState.Idle(false),
                animations = QuickToggleFieldState.Idle(AnimationsSummary.AllOff),
            ),
        )

        assertTrue(p.darkThemeToggleForTest.isSelected)
        assertFalse(p.showTouchesToggleForTest.isSelected)
        assertTrue(p.animationsOffToggleForTest.isSelected)
        assertTrue(p.darkThemeToggleForTest.isEnabled)
    }

    fun `test update with QuickTogglesViewState Loading disables the toggles`() {
        val p = panel()

        p.update(QuickTogglesViewState())

        assertFalse(p.darkThemeToggleForTest.isEnabled)
        assertFalse(p.showTouchesToggleForTest.isEnabled)
        assertFalse(p.animationsOffToggleForTest.isEnabled)
    }
}

private fun javax.swing.JTextField.postActionEvent() {
    actionListeners.forEach { it.actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "")) }
}
