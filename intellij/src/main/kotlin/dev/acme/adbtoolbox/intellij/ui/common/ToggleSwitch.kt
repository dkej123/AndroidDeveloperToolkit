package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JToggleButton

/**
 * The 24×13 pill track / 10px knob switch supplied by `design/README.md` §5's Quick toggles rows
 * (on = `accent` track, off = `borderStrong` track, white knob sliding via `left`). No text/border
 * chrome of its own — a caller supplies the row's label/value text around it, the same way
 * [PresetChip] only draws its own chip rather than a whole row.
 */
class ToggleSwitch : JToggleButton() {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        preferredSize = Dimension(JBUI.scale(24), JBUI.scale(13))
        addItemListener { repaint() }
        getAccessibleContext().accessibleName = "Toggle"
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (isSelected) AdbToolboxTheme.Colors.accent else AdbToolboxTheme.Colors.borderStrong
            g2.fillRoundRect(0, 0, width, height, height, height)

            val knobSize = JBUI.scale(10)
            val knobInset = (height - knobSize) / 2
            val knobLeft = if (isSelected) width - knobSize - knobInset else knobInset
            g2.color = Color.WHITE
            g2.fillOval(knobLeft, knobInset, knobSize, knobSize)
        } finally {
            g2.dispose()
        }
    }
}
