package dev.acme.adbtoolbox.intellij.display

import dev.acme.adbtoolbox.domain.display.TalkBackProfile

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.application.display.developer.DeveloperOptionsViewState
import dev.acme.adbtoolbox.application.display.developer.DeveloperToggle
import dev.acme.adbtoolbox.application.display.toggles.DeviceSettingToggle
import dev.acme.adbtoolbox.application.display.toggles.DeviceSettingTogglesViewState
import dev.acme.adbtoolbox.domain.display.toggles.ScreenRotation
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
        onSetTalkBack: (Boolean) -> Unit = {},
        onSetDeveloperToggle: (DeveloperToggle, Boolean) -> Unit = { _, _ -> },
        onSetProcessLimit: (Int) -> Unit = {},
        onSetDeviceSettingToggle: (DeviceSettingToggle, Boolean) -> Unit = { _, _ -> },
        onSetRotation: (ScreenRotation) -> Unit = {},
    ) = DisplayPanel(
        onApplyFontScale, onResetFontScale, onApplyDensityPreset, onApplyCustomDensity, onResetDensity,
        onSetDarkTheme, onSetShowTouches, onSetAnimationsOff, onSetTalkBack,
        onSetDeveloperToggle = onSetDeveloperToggle,
        onSetProcessLimit = onSetProcessLimit,
        onSetDeviceSettingToggle = onSetDeviceSettingToggle,
        onSetRotation = onSetRotation,
    )

    // ---- Font scale ----

    fun `test choosing a font-scale preset invokes onApplyFontScale with its value`() {
        var applied: Double? = null
        val p = panel(onApplyFontScale = { applied = it })

        p.chooseForTest(p.fontComboForTest, "1.3×")

        assertEquals(1.3, applied)
    }

    fun `test choosing the still-confirmed scale while another is being applied applies it again`() {
        val applied = mutableListOf<Double>()
        val p = panel(onApplyFontScale = { applied += it })
        p.update(FontScaleState.Idle(current = 1.0))
        p.chooseForTest(p.fontComboForTest, "1.5×")
        // Until the readback confirms 1.5, the row still shows the confirmed 1× as selected.
        p.update(FontScaleState.Applying(current = 1.0, target = 1.5))

        p.chooseForTest(p.fontComboForTest, "1×  (default)")

        assertEquals(listOf(1.5, 1.0), applied)
    }

    fun `test choosing the confirmed font scale when nothing is pending applies nothing`() {
        val applied = mutableListOf<Double>()
        val p = panel(onApplyFontScale = { applied += it })
        p.update(FontScaleState.Idle(current = 1.0))

        p.chooseForTest(p.fontComboForTest, "1×  (default)")

        assertEquals(emptyList<Double>(), applied)
    }

    fun `test choosing Custom… reveals the custom disclosure row without applying`() {
        var invoked = false
        val p = panel(onApplyFontScale = { invoked = true })

        p.chooseForTest(p.fontComboForTest, "Custom…")

        assertTrue(p.fontCustomRowForTest.isVisible)
        assertFalse(invoked)
    }

    fun `test the two dropdowns sit side by side at dock width and stack when narrow`() {
        val p = panel()
        p.setSize(346, 620)
        recursivelyLayout(p)
        assertEquals(p.fontComboForTest.parent.y, p.densityComboForTest.parent.y)

        p.setSize(260, 620)
        recursivelyLayout(p)
        assertTrue(p.densityComboForTest.parent.y > p.fontComboForTest.parent.y)
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

        assertEquals("default", p.displayMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.textFaint, p.displayMetaLabelForTest.foreground)
        assertFalse(p.fontOverrideRowForTest.isVisible)
    }

    fun `test update with FontScaleState Idle overridden shows the applied value in amber and reveals the override row`() {
        val p = panel()

        p.update(FontScaleState.Idle(1.3))

        assertEquals("1.3× applied", p.displayMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.amber, p.displayMetaLabelForTest.foreground)
        assertEquals(AdbToolboxTheme.Colors.amber, p.fontComboForTest.foreground)
        assertEquals("warning", p.fontComboForTest.getClientProperty("JComponent.outline"))
        assertTrue(p.fontOverrideRowForTest.isVisible)

        p.update(FontScaleState.Idle(1.0))
        assertFalse(p.fontComboForTest.foreground == AdbToolboxTheme.Colors.amber)
        assertNull(p.fontComboForTest.getClientProperty("JComponent.outline"))
        assertFalse(p.fontOverrideRowForTest.isVisible)
    }

    fun `test update with FontScaleState Error shows the error message next to the custom field`() {
        val p = panel()

        p.update(FontScaleState.Error(current = 1.0, message = "Value must be between 0.25 and 5.0"))

        assertEquals("Value must be between 0.25 and 5.0", p.fontCustomErrorLabelForTest.text)
        assertTrue(p.fontCustomErrorLabelForTest.isVisible)
        assertTrue(p.fontCustomRowForTest.isVisible)
    }

    // ---- Display scale (density) ----

    fun `test choosing a density preset invokes onApplyDensityPreset with its percent`() {
        var applied: Int? = null
        val p = panel(onApplyDensityPreset = { applied = it })

        p.chooseForTest(p.densityComboForTest, "125%")

        assertEquals(125, applied)
    }

    fun `test choosing the still-confirmed density while another is being applied applies it again`() {
        val applied = mutableListOf<Int>()
        val p = panel(onApplyDensityPreset = { applied += it })
        p.update(DensityViewState.Idle(DensityReading(420, null)))
        p.chooseForTest(p.densityComboForTest, "125% · 525 dpi")
        p.update(DensityViewState.Applying(DensityReading(420, null)))

        p.chooseForTest(p.densityComboForTest, "100% · 420 dpi (physical)")

        assertEquals(listOf(125, 100), applied)
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

        assertEquals("default", p.displayMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.textFaint, p.displayMetaLabelForTest.foreground)
        assertFalse(p.densityOverrideRowForTest.isVisible)
        assertTrue(p.densityHelpLabelForTest.text.contains("420 dpi"))
    }

    fun `test update with DensityViewState Idle overridden shows the override dpi in amber and reveals the override row`() {
        val p = panel()

        p.update(DensityViewState.Idle(DensityReading(420, 525)))

        assertEquals("525 dpi applied", p.displayMetaLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.amber, p.displayMetaLabelForTest.foreground)
        assertTrue(p.densityOverrideRowForTest.isVisible)
    }

    fun `test the header meta names everything applied and an applied custom value joins its dropdown in order`() {
        val p = panel()

        p.update(FontScaleState.Idle(1.4))
        p.update(DensityViewState.Idle(DensityReading(420, 500)))

        assertEquals("1.4× · 500 dpi applied", p.displayMetaLabelForTest.text)
        val fontLabels = (0 until p.fontComboForTest.itemCount).map { label(p.fontComboForTest, it) }
        assertEquals(listOf("0.85×", "1×  (default)", "1.15×", "1.3×", "1.4×", "1.5×", "2×", "Custom…"), fontLabels)
        assertEquals("1.4×", label(p.fontComboForTest, p.fontComboForTest.selectedIndex))
        assertEquals("119% · 500 dpi", label(p.densityComboForTest, p.densityComboForTest.selectedIndex))
        assertEquals("125% · 525 dpi", label(p.densityComboForTest, p.densityComboForTest.selectedIndex + 1))
    }

    private fun label(combo: javax.swing.JComboBox<*>, index: Int): String {
        @Suppress("UNCHECKED_CAST")
        val renderer = combo.renderer as javax.swing.ListCellRenderer<Any?>
        return (renderer.getListCellRendererComponent(javax.swing.JList(), combo.getItemAt(index), index, false, false) as javax.swing.JLabel).text
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

    fun `test toggling TalkBack invokes onSetTalkBack and shows which commands it uses`() {
        var enabled: Boolean? = null
        val p = panel(onSetTalkBack = { enabled = it })
        p.update(QuickTogglesViewState(talkBack = QuickToggleFieldState.Idle(false), talkBackProfile = TalkBackProfile.Samsung))
        assertEquals("off · Samsung", p.talkBackValueLabelForTest.text)

        p.talkBackToggleForTest.doClick()
        assertEquals(true, enabled)

        p.update(QuickTogglesViewState(talkBack = QuickToggleFieldState.Idle(true), talkBackProfile = TalkBackProfile.Custom))
        assertTrue(p.talkBackToggleForTest.isSelected)
        assertEquals("on · custom", p.talkBackValueLabelForTest.text)
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
        assertEquals("night yes", p.darkThemeValueLabelForTest.text)
        assertFalse(p.showTouchesToggleForTest.isSelected)
        assertEquals("off", p.showTouchesValueLabelForTest.text)
        assertTrue(p.animationsOffToggleForTest.isSelected)
        assertEquals("scale 0×", p.animationsValueLabelForTest.text)
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

    fun `test clicking anywhere on a tile flips its switch`() {
        var enabled: Boolean? = null
        val p = panel(onSetDarkTheme = { enabled = it })
        p.update(QuickTogglesViewState(darkTheme = QuickToggleFieldState.Idle(false)))
        val tile = p.darkThemeTileForTest

        tile.dispatchEvent(java.awt.event.MouseEvent(tile, java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false))

        assertEquals(true, enabled)
    }

    fun `test clicking a loading tile does nothing`() {
        var enabled: Boolean? = null
        val p = panel(onSetDarkTheme = { enabled = it })
        p.update(QuickTogglesViewState())
        val tile = p.darkThemeTileForTest

        tile.dispatchEvent(java.awt.event.MouseEvent(tile, java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false))

        assertNull(enabled)
    }

    fun `test the section header counts the toggles that are on`() {
        val p = panel()
        assertEquals("—", p.togglesMetaLabelForTest.text)

        p.update(
            QuickTogglesViewState(
                darkTheme = QuickToggleFieldState.Idle(true),
                showTouches = QuickToggleFieldState.Idle(false),
                animations = QuickToggleFieldState.Idle(AnimationsSummary.AllOff),
                talkBack = QuickToggleFieldState.Idle(false),
            ),
        )
        assertEquals("2 of 17 on", p.togglesMetaLabelForTest.text)

        p.update(
            DeveloperOptionsViewState(
                toggles = DeveloperToggle.entries.associateWith { QuickToggleFieldState.Idle(it == DeveloperToggle.StayAwake) },
            ),
        )
        assertEquals("3 of 17 on", p.togglesMetaLabelForTest.text)
    }

    // ---- Task 059/064 setting toggles ----

    fun `test setting toggles render their readback and report clicks`() {
        val set = mutableListOf<Pair<DeviceSettingToggle, Boolean>>()
        val p = panel(onSetDeviceSettingToggle = { toggle, on -> set += toggle to on })
        p.update(
            DeviceSettingTogglesViewState(
                toggles = DeviceSettingToggle.entries.associateWith { QuickToggleFieldState.Idle(it == DeviceSettingToggle.BoldText) },
                rotation = QuickToggleFieldState.Idle(ScreenRotation.Auto),
            ),
        )

        assertEquals("weight +300", p.settingValueLabelForTest(DeviceSettingToggle.BoldText).text)
        assertEquals("off", p.settingValueLabelForTest(DeviceSettingToggle.GpuOverdraw).text)
        p.settingToggleForTest(DeviceSettingToggle.GpuOverdraw).doClick()
        assertEquals(listOf(DeviceSettingToggle.GpuOverdraw to true), set)
    }

    fun `test an unsupported toggle reads n-a with its reason and leaves the count`() {
        val p = panel()
        p.update(
            DeviceSettingTogglesViewState(
                toggles = DeviceSettingToggle.entries.associateWith {
                    if (it == DeviceSettingToggle.MobileData) QuickToggleFieldState.Error("Not supported on this device", null) else QuickToggleFieldState.Idle(false)
                },
            ),
        )

        assertEquals("n/a", p.settingValueLabelForTest(DeviceSettingToggle.MobileData).text)
        assertEquals("No cellular radio on this device", p.settingToggleForTest(DeviceSettingToggle.MobileData).toolTipText)
        assertFalse(p.settingToggleForTest(DeviceSettingToggle.MobileData).isEnabled)
        assertEquals("0 of 16 on", p.togglesMetaLabelForTest.text)
    }

    fun `test airplane and wi-fi block themselves while adb runs over wi-fi`() {
        val p = panel()
        p.update(DeviceSettingTogglesViewState(toggles = DeviceSettingToggle.entries.associateWith { QuickToggleFieldState.Idle(true) }))

        p.setConnectedOverWifi(true)

        assertEquals("adb over Wi-Fi", p.settingValueLabelForTest(DeviceSettingToggle.Wifi).text)
        assertFalse(p.settingToggleForTest(DeviceSettingToggle.AirplaneMode).isEnabled)
        assertTrue(p.settingToggleForTest(DeviceSettingToggle.Wifi).toolTipText.startsWith("Connected over Wi-Fi"))
        assertTrue(p.settingToggleForTest(DeviceSettingToggle.MobileData).isEnabled)

        p.setConnectedOverWifi(false)
        assertEquals("on", p.settingValueLabelForTest(DeviceSettingToggle.Wifi).text)
        assertTrue(p.settingToggleForTest(DeviceSettingToggle.Wifi).isEnabled)
    }

    fun `test rotation is a segmented control with an amber locked value`() {
        val chosen = mutableListOf<ScreenRotation>()
        val p = panel(onSetRotation = { chosen += it })
        assertFalse(p.rotationChipRowForTest.chips.first().isEnabled)

        p.update(DeviceSettingTogglesViewState(rotation = QuickToggleFieldState.Idle(ScreenRotation.Landscape)))

        assertEquals("landscape · locked", p.rotationValueLabelForTest.text)
        assertEquals(dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme.Colors.amber, p.rotationValueLabelForTest.foreground)
        p.rotationChipRowForTest.chips.first { it.value == ScreenRotation.Auto }.doClick()
        assertEquals(listOf(ScreenRotation.Auto), chosen)
    }

    fun `test tiles are grouped under appearance and developer captions`() {
        val p = panel()
        val texts = mutableListOf<String>()
        fun collect(component: java.awt.Component) {
            if (component is javax.swing.JLabel) texts += (component.accessibleContext.accessibleName ?: component.text)
            if (component is java.awt.Container) component.components.forEach(::collect)
        }
        collect(p)

        val appearance = texts.indexOf("Appearance & accessibility")
        val developer = texts.indexOf("Developer")
        val connectivity = texts.indexOf("Connectivity")
        assertTrue(appearance >= 0 && developer > appearance && connectivity > developer)
        val appearanceTiles = listOf("Dark theme", "Animations off", "Show touches", "TalkBack", "Bold text", "Invert colors")
        assertEquals(appearanceTiles, texts.subList(appearance + 1, developer).filter { it in appearanceTiles })
        assertTrue(texts.indexOf("Stay awake") > developer)
        assertTrue(texts.indexOf("Show layout bounds") in developer..connectivity)
        assertEquals(
            listOf("Airplane mode", "Wi-Fi", "Mobile data"),
            texts.drop(connectivity + 1).filter { it in listOf("Airplane mode", "Wi-Fi", "Mobile data") },
        )
        assertTrue(texts.indexOf("Rotation") > connectivity)
    }

    // ---- Developer-options toggles ----

    fun `test each developer toggle reports its own switch and the new selection`() {
        val set = mutableListOf<Pair<DeveloperToggle, Boolean>>()
        val p = panel(onSetDeveloperToggle = { toggle, enabled -> set += toggle to enabled })
        p.update(DeveloperOptionsViewState(toggles = DeveloperToggle.entries.associateWith { QuickToggleFieldState.Idle(false) }))

        DeveloperToggle.entries.forEach { p.developerToggleForTest(it).doClick() }

        assertEquals(DeveloperToggle.entries.map { it to true }, set)
    }

    fun `test developer toggles render the readback, Loading disables them`() {
        val p = panel()
        p.update(DeveloperOptionsViewState())
        assertFalse(p.developerToggleForTest(DeveloperToggle.StayAwake).isEnabled)

        p.update(
            DeveloperOptionsViewState(
                toggles = DeveloperToggle.entries.associateWith { QuickToggleFieldState.Idle(it == DeveloperToggle.StayAwake) },
            ),
        )

        assertTrue(p.developerToggleForTest(DeveloperToggle.StayAwake).isSelected)
        assertEquals("on", p.developerValueLabelForTest(DeveloperToggle.StayAwake).text)
        assertFalse(p.developerToggleForTest(DeveloperToggle.DontKeepActivities).isSelected)
        assertEquals("off", p.developerValueLabelForTest(DeveloperToggle.DontKeepActivities).text)
    }

    fun `test a developer toggle error stays clickable and explains itself in the tooltip`() {
        val p = panel()

        p.update(
            DeveloperOptionsViewState(
                toggles = mapOf(DeveloperToggle.ShowSurfaceUpdates to QuickToggleFieldState.Error("Needs adb root", null)),
            ),
        )

        assertTrue(p.developerToggleForTest(DeveloperToggle.ShowSurfaceUpdates).isEnabled)
        assertEquals("n/a", p.developerValueLabelForTest(DeveloperToggle.ShowSurfaceUpdates).text)
        assertEquals("Needs adb root", p.developerValueLabelForTest(DeveloperToggle.ShowSurfaceUpdates).toolTipText)
    }

    fun `test background process limit chips apply their limit and follow the readback`() {
        var applied: Int? = null
        val p = panel(onSetProcessLimit = { applied = it })
        p.update(DeveloperOptionsViewState(processLimit = QuickToggleFieldState.Idle(-1)))

        assertEquals(listOf("Standard", "0", "1", "2", "3", "4"), p.processLimitChipRowForTest.chips.map { it.label })
        assertEquals("standard", p.processLimitValueLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.textFaint, p.processLimitValueLabelForTest.foreground)

        p.processLimitChipRowForTest.chips.first { it.label == "2" }.doClick()
        assertEquals(2, applied)

        p.update(DeveloperOptionsViewState(processLimit = QuickToggleFieldState.Idle(2)))
        assertEquals("2 max", p.processLimitValueLabelForTest.text)
        assertEquals(AdbToolboxTheme.Colors.amber, p.processLimitValueLabelForTest.foreground)
        assertEquals(2, p.processLimitChipRowForTest.selectedValue)
    }

    fun `test a process limit outside the presets is shown without selecting a chip`() {
        val p = panel()

        p.update(DeveloperOptionsViewState(processLimit = QuickToggleFieldState.Idle(12)))

        assertEquals("12 max", p.processLimitValueLabelForTest.text)
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
