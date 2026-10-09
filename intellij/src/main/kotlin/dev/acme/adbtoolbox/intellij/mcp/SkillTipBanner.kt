package dev.acme.adbtoolbox.intellij.mcp

import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.WrappingText
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Amber tip at the top of the tool window (task 066, same treatment as the device bar's warning
 * banner): install the agent skill. One click installs it for Claude Code, "Other agents…" opens
 * Settings › AI agents, × hides it for good.
 */
class SkillTipBanner(
    private val installer: AgentSkillInstaller,
    private val onDismiss: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val runIo: (() -> Unit) -> Unit,
) : JPanel(BorderLayout()) {

    private val message = WrappingText(TIP, AdbToolboxTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f)), AdbToolboxTheme.Colors.amber)
    private val install = link("Install for Claude Code") { installForClaude() }
    private val other = link("Other agents…") { onOpenSettings() }
    private val links = JPanel(FlowLayout(FlowLayout.LEADING, JBUIScale.scale(12), 0)).apply {
        isOpaque = false
        add(install)
        add(other)
    }

    /** True after an install: the result stays on screen until the user closes it. */
    private var showingResult = false

    init {
        background = AdbToolboxTheme.Colors.amberBg
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

    /** Shows or hides the tip; [show] comes from [showSkillTip]. */
    fun render(show: Boolean) {
        if (showingResult) return
        if (isVisible != show) {
            isVisible = show
            parent?.revalidate()
        }
    }

    private fun installForClaude() {
        install.isEnabled = false
        runIo {
            val result = runCatching { installer.install(SkillAgent.ClaudeCode) }
            SwingUtilities.invokeLater {
                showingResult = true
                links.isVisible = false
                message.text = result.fold(
                    { "Skill installed for Claude Code in ${it.parent}. New agent sessions load it; other agents in Settings › AI agents." },
                    { "Couldn’t install the skill: ${it.message}. Try again from Settings › AI agents." },
                )
                revalidate()
            }
        }
    }

    private fun link(text: String, action: () -> Unit) = JButton(text).apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.accent
        border = JBUI.Borders.empty()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        margin = java.awt.Insets(0, 0, 0, 0)
        addActionListener { action() }
    }

    internal val messageForTest: String get() = message.text

    internal fun clickInstallForTest() = install.doClick()

    private companion object {
        const val TIP = "Tip: install the ADB Toolbox skill so coding agents know how to use the device tools well — " +
            "fewer calls, fewer tokens."
    }
}
