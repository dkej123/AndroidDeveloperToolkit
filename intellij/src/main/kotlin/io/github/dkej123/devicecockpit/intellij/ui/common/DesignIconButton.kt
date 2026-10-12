package io.github.dkej123.devicecockpit.intellij.ui.common

import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton

/**
 * A 22px icon button (refresh, clear…): the glyph alone at rest, a `hover` fill under the pointer
 * and a deeper one while pressed, so a click is visible even when its action finishes instantly.
 * The tooltip doubles as the accessible name.
 */
class DesignIconButton(icon: Icon, tooltip: String) : JButton(icon) {

    init {
        preferredSize = Dimension(AdbToolboxTheme.Sizes.iconButton, AdbToolboxTheme.Sizes.iconButton)
        margin = Insets(0, 0, 0, 0)
        toolTipText = tooltip
        getAccessibleContext().accessibleName = tooltip
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        isRolloverEnabled = true
        // A click must not leave keyboard focus (and its outline) behind; Tab still reaches it.
        isRequestFocusEnabled = false
    }

    /** How many `hover` layers sit under the glyph: 0 at rest, 1 under the pointer, 2 while pressed. */
    internal val fillLayers: Int
        get() = when {
            !isEnabled -> 0
            model.isPressed && model.isArmed -> 2
            model.isRollover -> 1
            else -> 0
        }

    override fun paintComponent(g: Graphics) {
        val layers = fillLayers
        if (layers > 0) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val radius = AdbToolboxTheme.Radii.button.toFloat() * 2
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), radius, radius)
                g2.color = AdbToolboxTheme.Colors.hover
                repeat(layers) { g2.fill(shape) }
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }
}
