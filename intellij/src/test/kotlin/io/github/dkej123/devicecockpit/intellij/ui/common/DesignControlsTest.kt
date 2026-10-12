package io.github.dkej123.devicecockpit.intellij.ui.common

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel

class DesignControlsTest : BasePlatformTestCase() {

    private fun fixed(width: Int, height: Int) = JPanel().apply { preferredSize = Dimension(width, height) }

    fun `test flex row centers every child vertically and pushes children after the fill to the trailing edge`() {
        val leading = fixed(20, 10)
        val spacer = flexSpacer()
        val trailing = fixed(30, 20)
        val row = flexRow(4, leading, spacer, trailing, fill = spacer).apply { setSize(200, 30) }

        row.doLayout()

        assertEquals(0, leading.x)
        assertEquals((30 - 10) / 2, leading.y)
        assertEquals(200 - 30, trailing.x)
        assertEquals((30 - 20) / 2, trailing.y)
        assertEquals(200 - 20 - 30 - 8, spacer.width)
    }

    fun `test flex row shrinks only the fill child when space runs out`() {
        val text = JLabel("a long banner message that cannot fit")
        val action = fixed(40, 16)
        val row = flexRow(6, text, action, fill = text).apply { setSize(100, 24) }

        row.doLayout()

        assertEquals(100 - 40 - 6, text.width)
        assertEquals(100 - 40, action.x)
        assertTrue(text.x + text.width <= action.x)
    }

    fun `test flex row preferred size is the sum of child widths and gaps and the tallest child`() {
        val row = flexRow(6, fixed(10, 5), fixed(20, 12))

        assertEquals(Dimension(36, 12), row.preferredSize)
    }

    fun `test design buttons use the supplied control heights and never stretch`() {
        val primary = DesignButton("Restart", DesignButtonStyle.PRIMARY)
        val secondary = DesignButton("Launch", DesignButtonStyle.SECONDARY)
        val danger = DesignButton("Uninstall", DesignButtonStyle.DANGER)

        assertEquals(DeviceCockpitTheme.Sizes.primaryButton, primary.preferredSize.height)
        assertEquals(DeviceCockpitTheme.Sizes.secondaryButton, secondary.preferredSize.height)
        assertEquals(JBUI.scale(24), danger.preferredSize.height)
        assertEquals(primary.preferredSize, primary.maximumSize)
        assertEquals(Font.BOLD, primary.font.style)
        assertEquals(Font.PLAIN, secondary.font.style)
    }

    fun `test switching a design button's style restyles it in place`() {
        val button = DesignButton("Enable proxy", DesignButtonStyle.PRIMARY)

        button.style = DesignButtonStyle.SECONDARY

        assertEquals(DeviceCockpitTheme.Colors.text, button.foreground)
        assertEquals(Font.PLAIN, button.font.style)
    }

    fun `test body and caption typography are regular weight`() {
        assertEquals(Font.PLAIN, DeviceCockpitTheme.Typography.body.style)
        assertEquals(Font.PLAIN, DeviceCockpitTheme.Typography.caption.style)
    }

    fun `test wrapping text grows in height when its width is constrained`() {
        val text = WrappingText("one two three four five six seven eight nine ten eleven twelve", DeviceCockpitTheme.Typography.caption, DeviceCockpitTheme.Colors.textFaint)
        val singleLine = text.preferredSize.height

        text.setSize(60, 1000)

        assertTrue(text.preferredSize.height > singleLine)
    }

    fun `test design combos leave their bounds unpainted so no default-panel rectangle shows around the field`() {
        assertFalse(designCombo<String> { it }.isOpaque)
    }

    fun `test a clicked screen tool button does not keep the focus outline but stays reachable by keyboard`() {
        val caret = ScreenToolButton(null, segment = ToolButtonSegment.CARET)

        assertFalse(caret.isRequestFocusEnabled)
        assertTrue(caret.isFocusable)
    }

    fun `test icon buttons fill under the pointer and deeper while pressed`() {
        val button = DesignIconButton(com.intellij.icons.AllIcons.Actions.Refresh, "Refresh device list")

        assertEquals(0, button.fillLayers)
        button.model.isRollover = true
        assertEquals(1, button.fillLayers)
        button.model.isArmed = true
        button.model.isPressed = true
        assertEquals(2, button.fillLayers)
        assertFalse(button.isRequestFocusEnabled)
        assertEquals("Refresh device list", button.getAccessibleContext().accessibleName)
    }
}
