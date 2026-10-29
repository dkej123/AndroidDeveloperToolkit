package io.github.dkej123.devicecockpit.intellij.ui.common

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import javax.swing.Icon

/**
 * The Android robot's head, drawn as a vector in the `green` token: the tile for "Any app" (Android picks the
 * handler), apps the plugin has no icon for (system apps), and the home / lock screens (user request, 2026-10-29).
 */
class AndroidRobotIcon(private val size: Int) : Icon {
    override fun getIconWidth() = JBUI.scale(size)
    override fun getIconHeight() = JBUI.scale(size)
    override fun paintIcon(c: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.translate(x, y)
            val s = iconWidth.toFloat()
            g.color = DeviceCockpitTheme.Colors.green
            // Antennae, then the half-dome head sitting in the lower part of the tile.
            g.stroke = BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Float(s * 0.30f, s * 0.20f, s * 0.38f, s * 0.36f))
            g.draw(Line2D.Float(s * 0.70f, s * 0.20f, s * 0.62f, s * 0.36f))
            g.fill(Arc2D.Float(s * 0.10f, s * 0.30f, s * 0.80f, s * 0.80f, 0f, 180f, Arc2D.PIE))
            g.color = DeviceCockpitTheme.Colors.panel
            val eye = s * 0.10f
            g.fill(Ellipse2D.Float(s * 0.32f - eye / 2, s * 0.55f - eye / 2, eye, eye))
            g.fill(Ellipse2D.Float(s * 0.68f - eye / 2, s * 0.55f - eye / 2, eye, eye))
        } finally { g.dispose() }
    }
}
