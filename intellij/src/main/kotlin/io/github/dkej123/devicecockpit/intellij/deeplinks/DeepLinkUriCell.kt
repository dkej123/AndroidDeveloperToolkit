package io.github.dkej123.devicecockpit.intellij.deeplinks

import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import java.awt.*
import javax.swing.JComponent

/** Two text runs, with a shared end ellipsis: scheme/authority in bold and the rest dimmed. */
internal class DeepLinkUriCell(private val uri: String, private val fill: Color) : JComponent() {
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = fill; g.fillRect(0, 0, width, height)
            val head = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*").find(uri)?.value ?: uri.substringBefore(':') + ":"
            val available = (width - com.intellij.util.ui.JBUI.scale(10)).coerceAtLeast(0)
            g.font = DeepLinkVisuals.mono(12f, true)
            val baseline = (height - g.fontMetrics.height) / 2 + g.fontMetrics.ascent
            g.color = DeviceCockpitTheme.Colors.text
            val headWidth = g.fontMetrics.stringWidth(head)
            g.drawString(ellipsize(head, g.fontMetrics, available), 0, baseline)
            if (headWidth < available) {
                g.font = DeepLinkVisuals.mono(12f)
                g.color = DeviceCockpitTheme.Colors.textDim
                g.drawString(ellipsize(uri.removePrefix(head), g.fontMetrics, available - headWidth), headWidth, baseline)
            }
        } finally { g.dispose() }
    }
}
