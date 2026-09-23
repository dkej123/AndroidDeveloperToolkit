package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import kotlin.math.roundToInt

/**
 * IntelliJ/Swing mapping of the repository-owned values in `design/tokens/tokens.json`.
 *
 * Color order follows [JBColor]'s light-then-dark constructor. Pixel measurements are resolved
 * lazily so they follow the IDE's current UI scale. Durations, counts, and opacity are deliberately
 * not display-scaled because their token units are time, count, and ratio rather than pixels.
 */
object AdbToolboxTheme {

    object Colors {
        val bg = themed(rgb(0xf7f8fa), rgb(0x1e1f22))
        val panel = themed(rgb(0xffffff), rgb(0x242629))
        val header = themed(rgb(0xf2f3f5), rgb(0x2b2d30))
        val field = themed(rgb(0xffffff), rgb(0x1e1f22))
        val border = themed(rgb(0xe1e3e8), rgba(0xffffff, 0.08))
        val borderStrong = themed(rgb(0xcfd2d8), rgba(0xffffff, 0.15))
        val text = themed(rgb(0x1e1f22), rgb(0xdfe1e5))
        val textDim = themed(rgb(0x63666b), rgb(0x8a8d93))
        val textFaint = themed(rgb(0x93969b), rgb(0x6b6e74))
        val accent = themed(rgb(0x3574f0), rgb(0x548af7))
        val accentBg = themed(rgba(0x3574f0, 0.10), rgba(0x548af7, 0.16))
        val accentBorder = themed(rgba(0x3574f0, 0.50), rgba(0x548af7, 0.50))
        val brand = themed(rgb(0x0e8a80), rgb(0x16a79b))
        val brandBg = themed(rgba(0x0e8a80, 0.10), rgba(0x16a79b, 0.16))
        val brandBorder = themed(rgba(0x0e8a80, 0.45), rgba(0x16a79b, 0.50))
        val green = themed(rgb(0x2f8f52), rgb(0x66b578))
        val greenBg = themed(rgba(0x2f8f52, 0.10), rgba(0x66b578, 0.14))
        val amber = themed(rgb(0xa9761f), rgb(0xd9a441))
        val amberBg = themed(rgba(0xa9761f, 0.10), rgba(0xd9a441, 0.14))
        val red = themed(rgb(0xc9424a), rgb(0xe0656b))
        val redBg = themed(rgba(0xc9424a, 0.09), rgba(0xe0656b, 0.14))
        val redBorder = themed(rgba(0xc9424a, 0.50), rgba(0xe0656b, 0.50))
        val hover = themed(rgba(0x000000, 0.045), rgba(0xffffff, 0.055))
        val scrim = themed(rgba(0x1e2024, 0.28), rgba(0x08090b, 0.55))
    }

    data class SeverityPalette(
        val level: JBColor,
        val message: JBColor,
        val rowBg: JBColor? = null,
        val weight: Int = Font.PLAIN,
    )

    object LogSeverityColors {
        val verbose = palette(0x93969b, 0x93969b, 0x6b6e74, 0x6b6e74)
        val debug = palette(0x2d5fb0, 0x4c5461, 0x8fb7f5, 0xb6c3d1)
        val info = palette(0x2f8f52, 0x1e1f22, 0x66b578, 0xdfe1e5)
        val warn = palette(0xa9761f, 0x8a6417, 0xd9a441, 0xe6c584)
        val error = palette(0xc9424a, 0xa8323a, 0xe0656b, 0xf0999d)
        val assert = SeverityPalette(
            level = themed(rgb(0x96222a), rgb(0xff8f95)),
            message = themed(rgb(0x96222a), rgb(0xff8f95)),
            rowBg = themed(rgba(0xc9424a, 0.10), rgba(0xe0656b, 0.14)),
            weight = Font.BOLD,
        )
        val timestamp = themed(rgb(0x93969b), rgb(0x6b6e74))
        val tag = themed(rgb(0x63666b), rgb(0x8a8d93))
        val searchHit = themed(rgba(0xa9761f, 0.30), rgba(0xd9a441, 0.35))
    }

    object Spacing {
        val s1 get() = JBUI.scale(2)
        val s2 get() = JBUI.scale(4)
        val s3 get() = JBUI.scale(6)
        val s4 get() = JBUI.scale(8)
        val s5 get() = JBUI.scale(12)
        val s6 get() = JBUI.scale(16)

        /** The prototype's 10px horizontal inset for section rows (`padding: 0 10px` throughout
         * `ADB Toolbox Plugin.dc.html`); not part of the 2/4/6/8/12/16 scale but used by every view. */
        val sectionInset get() = JBUI.scale(10)
    }

    object Sizes {
        val iconButton get() = JBUI.scale(22)
        val field get() = JBUI.scale(24)
        val secondaryButton get() = JBUI.scale(26)
        val primaryButton get() = JBUI.scale(26)
        val railButton get() = JBUI.scale(26)
        val rail get() = JBUI.scale(34)
        val titleBar get() = JBUI.scale(32)
        val deviceBar get() = JBUI.scale(30)
        val statusBar get() = JBUI.scale(22)
        val toolbarRow get() = JBUI.scale(26)
        val appRow get() = JBUI.scale(34)
        val listRow get() = JBUI.scale(28)
        val toggleRow get() = JBUI.scale(28)
        val logLineHeight get() = JBUI.scale(16)
        val logTagColumn get() = JBUI.scale(104)
    }

    object Radii {
        val field get() = JBUI.scale(4)
        val button get() = JBUI.scale(5)
        val chip get() = JBUI.scale(5)
        val railButton get() = JBUI.scale(6)
        val card get() = JBUI.scale(8)
        val pill get() = JBUI.scale(999)
    }

    /** Popup-only shadow from the light/dark design-system specimens. */
    object Shadows {
        val popupOffsetY get() = JBUI.scale(if (JBColor.isBright()) 18 else 20)
        val popupBlurRadius get() = JBUI.scale(if (JBColor.isBright()) 44 else 50)
        val popupColor = themed(rgba(0x14141e, 0.18), rgba(0x000000, 0.50))
    }

    object Typography {
        val title: Font get() = labelFont(14f, Font.BOLD)
        val sectionTitle: Font get() = labelFont(12.5f, Font.BOLD)

        // The handoff defines body and caption relatively so user IDE font preferences win.
        // Explicitly regular (tokens: body/caption weight 400): the family and size follow the IDE,
        // but a look-and-feel whose default label font is bold must not make all body copy bold.
        val body: Font get() = JBUI.Fonts.label().deriveFont(Font.PLAIN)
        val caption: Font get() = JBFont.small().deriveFont(Font.PLAIN)
        val groupLabel: Font get() = labelFont(9.5f, Font.BOLD)
        val mono: Font get() = editorFont(11f)
        val monoMeta: Font get() = editorFont(9.5f)

        const val groupLabelLetterSpacing = 0.06f
        const val groupLabelUppercase = true
    }

    object Motion {
        const val spinMs = 700
        const val pulseMs = 1_400
        const val toggleKnobMs = 150
        const val toastAutoDismissMs = 4_000
        const val toastMaxStack = 3
    }

    object Breakpoints {
        val narrow get() = JBUI.scale(340)
        val wide get() = JBUI.scale(470)
        val defaultDock get() = JBUI.scale(380)
    }

    object States {
        const val disabledOpacity = 0.45f
    }

    private fun editorFont(size: Float): Font =
        EditorColorsManager.getInstance()
            .globalScheme
            .getFont(EditorFontType.PLAIN)
            .deriveFont(JBUI.scale(size))

    private fun labelFont(size: Float, style: Int): Font =
        JBUI.Fonts.label().deriveFont(style, JBUI.scale(size))

    private fun palette(
        lightLevel: Int,
        lightMessage: Int,
        darkLevel: Int,
        darkMessage: Int,
    ) = SeverityPalette(
        level = themed(rgb(lightLevel), rgb(darkLevel)),
        message = themed(rgb(lightMessage), rgb(darkMessage)),
    )

    private fun themed(light: Color, dark: Color) = JBColor(light, dark)

    private fun rgb(value: Int) = Color(value)

    private fun rgba(rgb: Int, alpha: Double) = Color(
        (rgb shr 16) and 0xff,
        (rgb shr 8) and 0xff,
        rgb and 0xff,
        (alpha * 255).roundToInt().coerceIn(0, 255),
    )
}
