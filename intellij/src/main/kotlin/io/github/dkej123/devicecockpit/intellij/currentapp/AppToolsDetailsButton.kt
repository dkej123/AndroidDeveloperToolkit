package io.github.dkej123.devicecockpit.intellij.currentapp

import com.intellij.icons.AllIcons
import com.intellij.ui.scale.JBUIScale
import io.github.dkej123.devicecockpit.intellij.icons.DeviceCockpitIcons
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton

/** One button owns the whole surface, including its icons, copy and empty space. */
internal class AppToolsDetailsButton : JButton("App tools & details") {
    private val subtitle = "Deep links · Permissions · Shared prefs · Databases"
    private val appsIcon = DeviceCockpitIcons.tinted(DeviceCockpitIcons.Views.apps, DeviceCockpitTheme.Colors.accent)
    private val arrow = DeviceCockpitIcons.tinted(AllIcons.General.ChevronRight, DeviceCockpitTheme.Colors.accent)
    private val padding get() = DeviceCockpitTheme.Spacing.s5
    private val gap get() = DeviceCockpitTheme.Spacing.s4
    private val captionFont get() = DeviceCockpitTheme.Typography.caption

    init {
        font = DeviceCockpitTheme.Typography.body.deriveFont(Font.BOLD)
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        border = null
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().apply {
            accessibleName = text
            accessibleDescription = subtitle
        }
    }

    private fun subtitleLines(): List<String> {
        val rowWidth = parent?.let { it.width - it.insets.left - it.insets.right }?.takeIf { it > 0 }
        val available = ((rowWidth ?: width.takeIf { it > 0 } ?: DeviceCockpitTheme.Breakpoints.defaultDock) -
            padding * 2 - appsIcon.iconWidth - arrow.iconWidth - gap * 2).coerceAtLeast(1)
        val metrics = getFontMetrics(captionFont)
        val lines = mutableListOf<String>()
        var line = ""
        subtitle.split(" · ").forEach { item ->
            val candidate = if (line.isEmpty()) item else "$line · $item"
            if (line.isNotEmpty() && metrics.stringWidth(candidate) > available) {
                lines += line
                line = item
            } else {
                line = candidate
            }
        }
        lines += line
        return lines
    }

    override fun getPreferredSize(): Dimension = Dimension(
        getFontMetrics(font).stringWidth(text) + padding * 2 + appsIcon.iconWidth + arrow.iconWidth + gap * 2,
        padding * 2 + getFontMetrics(font).height + DeviceCockpitTheme.Spacing.s1 +
            getFontMetrics(captionFont).height * subtitleLines().size,
    )

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val canvas = g.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val stroke = JBUIScale.scale(1f)
            val arc = (DeviceCockpitTheme.Radii.card * 2).toFloat()
            val shape = RoundRectangle2D.Float(stroke / 2, stroke / 2, width - stroke, height - stroke, arc, arc)
            canvas.color = DeviceCockpitTheme.Colors.accentBg
            canvas.fill(shape)
            if (model.isRollover || model.isPressed) {
                canvas.color = DeviceCockpitTheme.Colors.hover
                canvas.fill(shape)
            }
            canvas.color = if (isFocusOwner || model.isPressed) DeviceCockpitTheme.Colors.accent else DeviceCockpitTheme.Colors.accentBorder
            canvas.stroke = BasicStroke(stroke)
            canvas.draw(shape)
            appsIcon.paintIcon(this, canvas, padding, (height - appsIcon.iconHeight) / 2)
            arrow.paintIcon(this, canvas, width - padding - arrow.iconWidth, (height - arrow.iconHeight) / 2)
            val x = padding + appsIcon.iconWidth + gap
            canvas.clipRect(x, 0, (width - x - padding - arrow.iconWidth - gap).coerceAtLeast(0), height)
            canvas.font = font
            canvas.color = DeviceCockpitTheme.Colors.text
            var baseline = padding + canvas.fontMetrics.ascent
            canvas.drawString(text, x, baseline)
            baseline += canvas.fontMetrics.descent + canvas.fontMetrics.leading + DeviceCockpitTheme.Spacing.s1
            canvas.font = captionFont
            canvas.color = DeviceCockpitTheme.Colors.textDim
            baseline += canvas.fontMetrics.ascent
            subtitleLines().forEach { line ->
                canvas.drawString(line, x, baseline)
                baseline += canvas.fontMetrics.height
            }
        } finally {
            canvas.dispose()
        }
    }
}
