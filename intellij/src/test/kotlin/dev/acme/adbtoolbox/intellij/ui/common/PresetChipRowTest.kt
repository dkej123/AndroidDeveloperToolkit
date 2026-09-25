package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

class PresetChipRowTest : BasePlatformTestCase() {

    fun `test row exposes supplied presets and exactly one selection`() {
        val row = PresetChipRow(
            choices = listOf(
                PresetChipChoice(0.85, "0.85×"),
                PresetChipChoice(1.0, "1×", isDefault = true),
                PresetChipChoice(1.15, "1.15×"),
            ),
            selected = 1.0,
        )

        assertEquals(listOf("0.85×", "1×", "1.15×"), row.chips.map { it.text })
        assertEquals(listOf(false, true, false), row.chips.map { it.isSelected })
    }

    fun `test clicking a preset changes the one selected value and emits it`() {
        val row = fontScaleRow(selected = 1.0)
        val selections = mutableListOf<Double>()
        row.onSelectionChanged = selections::add

        row.chips[2].doClick()

        assertEquals(1.15, row.selectedValue)
        assertEquals(listOf(false, false, true), row.chips.map { it.isSelected })
        assertEquals(listOf(1.15), selections)
    }

    fun `test state-driven selection update does not emit a user event`() {
        val row = fontScaleRow(selected = 1.0)
        var emissionCount = 0
        row.onSelectionChanged = { emissionCount++ }

        row.setSelectedValue(0.85)

        assertEquals(0.85, row.selectedValue)
        assertEquals(0, emissionCount)
    }

    fun `test right and left arrows traverse and select chips`() {
        val row = fontScaleRow(selected = 1.0)

        invokeKey(row.chips[1], KeyEvent.VK_RIGHT)
        assertEquals(1.15, row.selectedValue)

        invokeKey(row.chips[2], KeyEvent.VK_LEFT)

        assertEquals(1.0, row.selectedValue)
    }

    fun `test chip dimensions and custom treatment come from supplied design`() {
        val row = PresetChipRow(
            choices = listOf(PresetChipChoice("custom", "Custom…", kind = PresetChipKind.CUSTOM)),
            selected = "custom",
        )

        assertEquals(AdbToolboxTheme.Sizes.iconButton, row.chips.single().preferredSize.height)
        assertEquals(PresetChipKind.CUSTOM, row.chips.single().kind)
        assertTrue(row.chips.single().border is DashedChipBorder)
    }

    fun `test every chip label fits at its preferred size whatever button UI the IDE theme installs`() {
        // Regression (docs/e2e-testing.md): under Android Studio's theme the button UI added its own
        // wide insets and every preset rendered as "0.8…", "1.1…", "Custo…".
        val row = PresetChipRow(
            choices = listOf("0.85×", "1.15×", "Custom…").map { PresetChipChoice(it, it) },
            selected = "0.85×",
        )
        val themeUi = javax.swing.UIManager.get("ToggleButtonUI")
        javax.swing.UIManager.put("ToggleButtonUI", com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI::class.java.name)
        try {
            row.chips.forEach { it.updateUI() } // what Android Studio's theme does
        } finally {
            javax.swing.UIManager.put("ToggleButtonUI", themeUi)
        }
        for (chip in row.chips) {
            chip.size = chip.preferredSize
            val insets = chip.insets
            val view = java.awt.Rectangle(insets.left, insets.top, chip.width - insets.left - insets.right, chip.height - insets.top - insets.bottom)
            val shown = javax.swing.SwingUtilities.layoutCompoundLabel(
                chip, chip.getFontMetrics(chip.font), chip.text, null,
                chip.verticalAlignment, chip.horizontalAlignment, chip.verticalTextPosition, chip.horizontalTextPosition,
                view, java.awt.Rectangle(), java.awt.Rectangle(), 0,
            )
            assertEquals(chip.text, shown)
            // Chips paint their own surface; the theme's button UI must not re-layout the label.
            assertEquals(javax.swing.plaf.basic.BasicToggleButtonUI::class.java, chip.ui.javaClass)
        }
    }

    fun `test default selection is neutral while a selected override is amber`() {
        val row = fontScaleRow(selected = 1.0)

        assertEquals(AdbToolboxTheme.Colors.text, row.chips[1].foreground)
        assertEquals(java.awt.Font.BOLD, row.chips[1].font.style)

        row.setSelectedValue(1.15)

        assertEquals(AdbToolboxTheme.Colors.amber, row.chips[2].foreground)
        assertEquals(AdbToolboxTheme.Colors.amberBg, row.chips[2].background)
        assertEquals(AdbToolboxTheme.Colors.textDim, row.chips[1].foreground)
    }

    fun `test row rejects ambiguous choices and an unknown initial selection`() {
        assertFailsWithIllegalArgument {
            PresetChipRow(
                choices = listOf(PresetChipChoice(1, "one"), PresetChipChoice(1, "also one")),
                selected = 1,
            )
        }
        assertFailsWithIllegalArgument {
            PresetChipRow(
                choices = listOf(PresetChipChoice(1, "one")),
                selected = 2,
            )
        }
    }

    // ---- Task 051: `design/README.md`'s narrow-width rule — preset chip rows wrap rather than
    // clip or require horizontal scrolling ----

    fun `test the row lays out chips onto multiple lines when narrower than one row's content`() {
        val row = PresetChipRow(
            choices = listOf(
                PresetChipChoice(0.85, "0.85×"),
                PresetChipChoice(1.0, "1×", isDefault = true),
                PresetChipChoice(1.15, "1.15×"),
                PresetChipChoice(1.3, "1.3×"),
                PresetChipChoice(1.5, "1.5×"),
            ),
            selected = 1.0,
        )
        val oneLineWidth = row.preferredSize.width

        row.setSize(oneLineWidth / 2, Int.MAX_VALUE / 2)
        row.doLayout()

        val distinctRowTops = row.chips.map { it.y }.distinct()
        assertTrue("expected wrapping onto more than one line", distinctRowTops.size > 1)
    }

    private fun fontScaleRow(selected: Double) = PresetChipRow(
        choices = listOf(
            PresetChipChoice(0.85, "0.85×"),
            PresetChipChoice(1.0, "1×", isDefault = true),
            PresetChipChoice(1.15, "1.15×"),
        ),
        selected = selected,
    )

    private fun invokeKey(component: JComponent, keyCode: Int) {
        val keyStroke = KeyStroke.getKeyStroke(keyCode, 0)
        val actionKey = component.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke)
        assertNotNull("expected a key binding for $keyCode", actionKey)
        val action = component.actionMap.get(actionKey)
        assertNotNull(action)
        action.actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, null))
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
