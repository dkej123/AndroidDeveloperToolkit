package io.github.dkej123.devicecockpit.intellij.ui.common

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font

class DeviceCockpitThemeTest : BasePlatformTestCase() {

    fun `test repository design theme is available to IntelliJ UI`() {
        Class.forName("io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme")
    }

    fun `test semantic colors retain supplied light and dark variants including alpha`() {
        assertVariants(DeviceCockpitTheme.Colors.bg, 0xfff7f8fa, 0xff1e1f22)
        assertVariants(DeviceCockpitTheme.Colors.panel, 0xffffffff, 0xff242629)
        assertVariants(DeviceCockpitTheme.Colors.header, 0xfff2f3f5, 0xff2b2d30)
        assertVariants(DeviceCockpitTheme.Colors.field, 0xffffffff, 0xff1e1f22)
        assertVariants(DeviceCockpitTheme.Colors.border, 0xffe1e3e8, 0x14ffffff)
        assertVariants(DeviceCockpitTheme.Colors.borderStrong, 0xffcfd2d8, 0x26ffffff)
        assertVariants(DeviceCockpitTheme.Colors.text, 0xff1e1f22, 0xffdfe1e5)
        assertVariants(DeviceCockpitTheme.Colors.textDim, 0xff63666b, 0xff8a8d93)
        assertVariants(DeviceCockpitTheme.Colors.textFaint, 0xff93969b, 0xff6b6e74)
        assertVariants(DeviceCockpitTheme.Colors.accent, 0xff3574f0, 0xff548af7)
        assertVariants(DeviceCockpitTheme.Colors.accentBg, 0x1a3574f0, 0x29548af7)
        assertVariants(DeviceCockpitTheme.Colors.accentBorder, 0x803574f0.toInt(), 0x80548af7.toInt())
        assertVariants(DeviceCockpitTheme.Colors.brand, 0xff0e8a80, 0xff16a79b)
        assertVariants(DeviceCockpitTheme.Colors.brandBg, 0x1a0e8a80, 0x2916a79b)
        assertVariants(DeviceCockpitTheme.Colors.brandBorder, 0x730e8a80, 0x8016a79b.toInt())
        assertVariants(DeviceCockpitTheme.Colors.green, 0xff2f8f52, 0xff66b578)
        assertVariants(DeviceCockpitTheme.Colors.greenBg, 0x1a2f8f52, 0x2466b578)
        assertVariants(DeviceCockpitTheme.Colors.amber, 0xffa9761f, 0xffd9a441)
        assertVariants(DeviceCockpitTheme.Colors.amberBg, 0x1aa9761f, 0x24d9a441)
        assertVariants(DeviceCockpitTheme.Colors.red, 0xffc9424a, 0xffe0656b)
        assertVariants(DeviceCockpitTheme.Colors.redBg, 0x17c9424a, 0x24e0656b)
        assertVariants(DeviceCockpitTheme.Colors.redBorder, 0x80c9424a.toInt(), 0x80e0656b.toInt())
        assertVariants(DeviceCockpitTheme.Colors.hover, 0x0b000000, 0x0effffff)
        assertVariants(DeviceCockpitTheme.Colors.scrim, 0x471e2024, 0x8c08090b.toInt())
        assertVariants(DeviceCockpitTheme.Shadows.popupColor, 0x2e14141e, 0x80000000.toInt())
    }

    fun `test logcat palette maps every supplied level and auxiliary color`() {
        with(DeviceCockpitTheme.LogSeverityColors) {
            assertPalette(verbose, 0xff93969b, 0xff93969b, 0xff6b6e74, 0xff6b6e74)
            assertPalette(debug, 0xff2d5fb0, 0xff4c5461, 0xff8fb7f5, 0xffb6c3d1)
            assertPalette(info, 0xff2f8f52, 0xff1e1f22, 0xff66b578, 0xffdfe1e5)
            assertPalette(warn, 0xffa9761f, 0xff8a6417, 0xffd9a441, 0xffe6c584)
            assertPalette(error, 0xffc9424a, 0xffa8323a, 0xffe0656b, 0xfff0999d)
            assertPalette(assert, 0xff96222a, 0xff96222a, 0xffff8f95, 0xffff8f95)
            assertNull(error.rowBg)
            assertEquals(Font.PLAIN, error.weight)
            assertEquals(Font.BOLD, assert.weight)
            assertVariants(assert.rowBg!!, 0x1ac9424a, 0x24e0656b)
            assertVariants(timestamp, 0xff93969b, 0xff6b6e74)
            assertVariants(tag, 0xff63666b, 0xff8a8d93)
            assertVariants(searchHit, 0x4da9761f, 0x59d9a441)
        }
    }

    fun `test pixel metrics are scaled through JBUI`() {
        assertEquals(listOf(2, 4, 6, 8, 12, 16).map { JBUI.scale(it) }, with(DeviceCockpitTheme.Spacing) {
            listOf(s1, s2, s3, s4, s5, s6)
        })
        assertEquals(JBUI.scale(16), DeviceCockpitTheme.Spacing.s6)
        assertEquals(
            listOf(22, 24, 26, 26, 26, 34, 32, 30, 28, 26, 34, 28, 28, 16, 104, 22).map { JBUI.scale(it) },
            with(DeviceCockpitTheme.Sizes) {
                listOf(iconButton, field, secondaryButton, primaryButton, railButton, rail, titleBar,
                    deviceBar, statusBar, toolbarRow, appRow, listRow, toggleRow, logLineHeight, logTagColumn, logcatFooter)
            },
        )
        assertEquals(listOf(4, 5, 5, 6, 8, 999).map { JBUI.scale(it) }, with(DeviceCockpitTheme.Radii) {
            listOf(field, button, chip, railButton, card, pill)
        })
        assertEquals(listOf(340, 470, 380).map { JBUI.scale(it) }, with(DeviceCockpitTheme.Breakpoints) {
            listOf(narrow, wide, defaultDock)
        })
        assertTrue(DeviceCockpitTheme.Shadows.popupOffsetY in setOf(JBUI.scale(18), JBUI.scale(20)))
        assertTrue(DeviceCockpitTheme.Shadows.popupBlurRadius in setOf(JBUI.scale(44), JBUI.scale(50)))
    }

    fun `test non pixel motion and state values preserve their semantic units`() {
        assertEquals(700, DeviceCockpitTheme.Motion.spinMs)
        assertEquals(1_400, DeviceCockpitTheme.Motion.pulseMs)
        assertEquals(150, DeviceCockpitTheme.Motion.toggleKnobMs)
        assertEquals(4_000, DeviceCockpitTheme.Motion.toastAutoDismissMs)
        assertEquals(3, DeviceCockpitTheme.Motion.toastMaxStack)
        assertEquals(0.45f, DeviceCockpitTheme.States.disabledOpacity)
    }

    fun `test typography follows IDE label small and editor fonts`() {
        val label = JBUI.Fonts.label()
        val small = JBFont.small()
        val editor = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

        assertEquals(label.family, DeviceCockpitTheme.Typography.body.family)
        assertEquals(label.size2D, DeviceCockpitTheme.Typography.body.size2D)
        // Tokens: body/caption weight 400, independent of a look-and-feel's default label style.
        assertEquals(Font.PLAIN, DeviceCockpitTheme.Typography.body.style)
        assertEquals(small.family, DeviceCockpitTheme.Typography.caption.family)
        assertEquals(editor.family, DeviceCockpitTheme.Typography.mono.family)
        assertEquals(Font.BOLD, DeviceCockpitTheme.Typography.title.style)
        assertEquals(Font.BOLD, DeviceCockpitTheme.Typography.sectionTitle.style)
        assertEquals(Font.BOLD, DeviceCockpitTheme.Typography.groupLabel.style)
        assertEquals(JBUI.Fonts.label(14f).size2D, DeviceCockpitTheme.Typography.title.size2D)
        assertEquals(JBUI.Fonts.label(12.5f).size2D, DeviceCockpitTheme.Typography.sectionTitle.size2D)
        assertEquals(JBUI.Fonts.label(9.5f).size2D, DeviceCockpitTheme.Typography.groupLabel.size2D)
        assertEquals(JBUI.scale(11f), DeviceCockpitTheme.Typography.mono.size2D)
        assertEquals(JBUI.scale(9.5f), DeviceCockpitTheme.Typography.monoMeta.size2D)
        assertEquals(0.06f, DeviceCockpitTheme.Typography.groupLabelLetterSpacing)
        assertTrue(DeviceCockpitTheme.Typography.groupLabelUppercase)
    }

    private fun assertPalette(
        palette: DeviceCockpitTheme.SeverityPalette,
        lightLevel: Number,
        lightMessage: Number,
        darkLevel: Number,
        darkMessage: Number,
    ) {
        assertVariants(palette.level, lightLevel, darkLevel)
        assertVariants(palette.message, lightMessage, darkMessage)
    }

    private fun assertVariants(color: JBColor, lightArgb: Number, darkArgb: Number) {
        val wasDark = !JBColor.isBright()
        try {
            JBColor.setDark(false)
            assertEquals(Color(lightArgb.toInt(), true), Color(color.rgb, true))
            JBColor.setDark(true)
            assertEquals(Color(darkArgb.toInt(), true), Color(color.rgb, true))
        } finally {
            JBColor.setDark(wasDark)
        }
    }
}
