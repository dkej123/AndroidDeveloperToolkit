package io.github.dkej123.devicecockpit.intellij.mcp

import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.WrappingText
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Amber tip at the top of the tool window (task 066, same treatment as the device bar's warning
 * banner), shown from the first run until agents are set up: one click turns the MCP server on
 * (when it is off) and connects Claude Code with its skill ([connect], which reports a result line);
 * "Other agents…" opens Settings › AI agents; × hides it for good, as does a finished connect.
 */
class SkillTipBanner(
    private val connect: (report: (String) -> Unit) -> Unit,
    private val onDismiss: () -> Unit,
    private val onOpenSettings: () -> Unit,
) : JPanel(BorderLayout()) {

    private val message = WrappingText(TIP_OFF, DeviceCockpitTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f)), DeviceCockpitTheme.Colors.amber)
    private val connectLink = link(TURN_ON) { connectClaudeCode() }
    private val other = link("Other agents…") { onOpenSettings() }
    private val links = JPanel(FlowLayout(FlowLayout.LEADING, JBUIScale.scale(12), 0)).apply {
        isOpaque = false
        add(connectLink)
        add(other)
    }

    /** True once a connect ran: the result stays on screen until the user closes it. */
    private var showingResult = false

    init {
        background = DeviceCockpitTheme.Colors.amberBg
        isOpaque = true
        border = JBUI.Borders.empty(6, 10)
        add(message, BorderLayout.CENTER)
        add(link("✕") {
            isVisible = false
            onDismiss()
        }.apply {
            toolTipText = "Hide this tip — it won’t come back"
            accessibleContext.accessibleName = "Hide tip"
        }, BorderLayout.EAST)
        add(links, BorderLayout.SOUTH)
        isVisible = false
    }

    /** Shows or hides the tip ([show] comes from [showSkillTip]); [serverOn] picks its wording. */
    fun render(show: Boolean, serverOn: Boolean) {
        if (showingResult) return
        message.text = if (serverOn) TIP_ON else TIP_OFF
        connectLink.text = if (serverOn) CONNECT else TURN_ON
        if (isVisible != show) {
            isVisible = show
            parent?.revalidate()
        }
    }

    private fun connectClaudeCode() {
        connectLink.isEnabled = false
        message.text = "Connecting Claude Code…"
        connect { result ->
            showingResult = true
            links.isVisible = false
            message.text = result
            onDismiss()
            revalidate()
        }
    }

    private fun link(text: String, action: () -> Unit) = JButton(text).apply {
        font = DeviceCockpitTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(10.5f))
        foreground = DeviceCockpitTheme.Colors.accent
        border = JBUI.Borders.empty()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        margin = java.awt.Insets(0, 0, 0, 0)
        addActionListener { action() }
    }

    internal val messageForTest: String get() = message.text
    internal val connectTextForTest: String get() = connectLink.text

    internal fun clickConnectForTest() = connectLink.doClick()

    private companion object {
        const val TURN_ON = "Turn on and connect Claude Code"
        const val CONNECT = "Connect Claude Code"
        const val TIP_OFF = "Let coding agents drive this device: one click turns on the local MCP server (Full control — " +
            "Uninstall and Clear data still ask here) and connects Claude Code with the Device Cockpit skill."
        const val TIP_ON = "Connect Claude Code to the MCP server and install the Device Cockpit skill, " +
            "so agents use the device tools well — fewer calls, fewer tokens."
    }
}
