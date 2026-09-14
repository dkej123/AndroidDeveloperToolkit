package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.domain.nav.ViewId
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * The five rail glyphs (`design/README.md` §2, `design/designs/ADB Toolbox Plugin.dc.html`'s
 * `RAIL` path data — task 043). The supplied production icon set under `design/icons/actions/` has
 * no rail-glyph entries of its own (only the toolbar/list action icons task 042 already imports),
 * so these are redrawn with plain [Graphics2D] primitives that trace the same 16x16 coordinate
 * space and 1.35px round-cap/round-join stroke as the prototype's inline SVG `path` data, rather
 * than a bundled/parsed SVG — a documented native simplification, not an invented visual rule.
 * [ViewId.Settings] has no rail glyph here: the rail uses the platform's own
 * [com.intellij.icons.AllIcons.General.Settings] for it (`design/README.md`'s Assets section:
 * "Prefer platform `AllIcons` where an exact equivalent exists ... settings").
 */
class RailGlyphIcon(private val viewId: ViewId, private val color: Color) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(16)
    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.stroke = BasicStroke(JBUI.scale(1.35f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            fun sx(v: Float): Int = x + Math.round(v * iconWidth / 16f)
            fun sy(v: Float): Int = y + Math.round(v * iconHeight / 16f)

            when (viewId) {
                ViewId.Device -> {
                    val arc = JBUI.scale(3)
                    g2.drawRoundRect(sx(5.2f), sy(2.4f), sx(10.8f) - sx(5.2f), sy(11.8f) - sy(2.4f), arc, arc)
                    g2.drawLine(sx(6.9f), sy(11.9f), sx(9.1f), sy(11.9f))
                }
                ViewId.Apps -> {
                    val side = sx(6.7f) - sx(2.7f)
                    g2.drawRect(sx(2.7f), sy(2.7f), side, side)
                    g2.drawRect(sx(9.3f), sy(2.7f), side, side)
                    g2.drawRect(sx(2.7f), sy(9.3f), side, side)
                    g2.drawRect(sx(9.3f), sy(9.3f), side, side)
                }
                ViewId.Display -> {
                    g2.drawRect(sx(2f), sy(3.6f), sx(14f) - sx(2f), sy(10.6f) - sy(3.6f))
                    g2.drawLine(sx(5.6f), sy(13.4f), sx(10.4f), sy(13.4f))
                }
                ViewId.Network -> {
                    val d = sx(13.4f) - sx(2.6f)
                    g2.drawOval(sx(2.6f), sy(2.6f), d, d)
                    g2.drawLine(sx(2.7f), sy(8f), sx(13.3f), sy(8f))
                    g2.drawOval(sx(5.7f), sy(2.6f), sx(10.3f) - sx(5.7f), d)
                }
                ViewId.Logcat -> {
                    g2.drawLine(sx(2.7f), sy(4.2f), sx(13.3f), sy(4.2f))
                    g2.drawLine(sx(2.7f), sy(8f), sx(9.9f), sy(8f))
                    g2.drawLine(sx(2.7f), sy(11.8f), sx(11.7f), sy(11.8f))
                }
                ViewId.Settings -> Unit
            }
        } finally {
            g2.dispose()
        }
    }
}
