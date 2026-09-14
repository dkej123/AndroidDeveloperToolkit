package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * The 7px device-state dot the device bar and picker rows use (`design/README.md` §1: "7px green
 * dot", "7px amber dot", "7px hollow dot (1.5px `textFaint` border)"). [filled] paints a solid
 * circle; `false` paints only the 1.5px outline (the "no device"/hollow treatment).
 */
class StatusDotIcon(private val color: Color, private val filled: Boolean, private val diameter: Int = 7) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(diameter)
    override fun getIconHeight(): Int = JBUI.scale(diameter)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            if (filled) {
                g2.fillOval(x, y, iconWidth, iconHeight)
            } else {
                val strokeWidth = JBUI.scale(1.5f)
                g2.stroke = BasicStroke(strokeWidth)
                val inset = strokeWidth / 2f
                g2.draw(
                    java.awt.geom.Ellipse2D.Float(
                        x + inset,
                        y + inset,
                        iconWidth - strokeWidth,
                        iconHeight - strokeWidth,
                    ),
                )
            }
        } finally {
            g2.dispose()
        }
    }
}
