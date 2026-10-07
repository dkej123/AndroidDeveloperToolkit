package dev.acme.adbtoolbox.intellij.mcp

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.acme.adbtoolbox.application.mcp.McpAccess
import dev.acme.adbtoolbox.application.mcp.McpTool
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

/** One agent's setup snippet (design §11 "Set up an agent"). */
data class AgentSnippet(val agent: String, val target: String, val text: String)

/** The setup snippets for [url] with [token]; Copy inserts the real token, the panel shows it masked. */
fun agentSnippets(url: String, token: String): List<AgentSnippet> = listOf(
    AgentSnippet(
        "Claude Code",
        "Run in a terminal (user scope)",
        "claude mcp add --transport http --scope user adb-toolbox $url --header \"Authorization: Bearer $token\"",
    ),
    AgentSnippet(
        "Codex CLI",
        "~/.codex/config.toml — and export ADB_TOOLBOX_TOKEN=$token",
        "[mcp_servers.adb-toolbox]\nurl = \"$url\"\nbearer_token_env_var = \"ADB_TOOLBOX_TOKEN\"",
    ),
    AgentSnippet(
        "Cursor",
        "~/.cursor/mcp.json",
        "{\n  \"mcpServers\": {\n    \"adb-toolbox\": {\n      \"url\": \"$url\",\n      \"headers\": { \"Authorization\": \"Bearer $token\" }\n    }\n  }\n}",
    ),
    AgentSnippet(
        "Gemini CLI",
        "~/.gemini/settings.json",
        "{\n  \"mcpServers\": {\n    \"adb-toolbox\": {\n      \"httpUrl\": \"$url\",\n      \"headers\": { \"Authorization\": \"Bearer $token\" }\n    }\n  }\n}",
    ),
    AgentSnippet("Other", "Any MCP client with Streamable HTTP", "URL: $url\nHeader: Authorization: Bearer $token"),
)

fun maskToken(token: String): String = if (token.length <= 8) "••••" else token.take(4) + "••••" + token.takeLast(4)

/**
 * Settings › Tools › ADB Toolbox › AI agents (MCP) (design §11): access level, server status, port
 * and token, setup snippets per agent and the exposed tools.
 */
class McpSettingsPanel(
    private val status: () -> McpStatus,
    private val token: () -> String,
    private val regenerate: () -> String,
    tools: List<McpTool>,
) : JPanel() {
    private val off = JBRadioButton("Off")
    private val readOnly = JBRadioButton("Read only")
    private val full = JBRadioButton("Full control")
    private val statusTitle = JBLabel().apply { font = AdbToolboxTheme.Typography.body.deriveFont(java.awt.Font.BOLD) }
    private val statusDetail = JBLabel().apply { foreground = AdbToolboxTheme.Colors.textDim }
    private val copyUrl = JButton("Copy URL").apply { addActionListener { status().port?.let { copy(url(it)) } } }
    private val statusBox = RoundedSurface(null, AdbToolboxTheme.Colors.border).apply {
        layout = BorderLayout(JBUI.scale(8), 0)
        border = JBUI.Borders.empty(8, 10)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusTitle)
            add(statusDetail)
        }, BorderLayout.CENTER)
        add(copyUrl, BorderLayout.EAST)
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(52))
    }
    private val portLabel = JBLabel().apply { font = AdbToolboxTheme.Typography.mono }
    private val tokenLabel = JBLabel().apply { font = AdbToolboxTheme.Typography.mono }
    private val copyToken = JButton("Copy").apply { addActionListener { copy(token()) } }
    private val regenerateToken = JButton("Regenerate").apply {
        toolTipText = "Disconnects every agent; they need the new token"
        addActionListener {
            regenerate()
            refresh()
        }
    }
    private val credentialsRow = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(JBLabel("Port"))
        add(portLabel)
        add(JBLabel("   Token"))
        add(tokenLabel)
        add(copyToken)
        add(regenerateToken)
    }
    private val snippetTabs = JBTabbedPane()
    private val snippetAreas = mutableListOf<Pair<JTextArea, JBLabel>>()
    private val toolsModel = object : DefaultTableModel(arrayOf("Tool", "Description", "Level"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val ticker = Timer(1000) { refresh() }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        ButtonGroup().apply {
            add(off)
            add(readOnly)
            add(full)
        }
        listOf(off, readOnly, full).forEach { it.addActionListener { if (isShowing) refresh() } }
        add(comment("A local MCP server lets coding agents drive the device selected in the tool window. It only listens on 127.0.0.1 and needs the token below."))
        add(radio(off, "No server runs. Default."))
        add(radio(readOnly, "Agents can see the screen, UI tree, logcat, app list, prefs and databases. They can’t change anything on the device."))
        add(radio(full, "Agents can also tap, type, change device settings and manage apps. Uninstall and Clear data still ask here first."))
        add(gap())
        add(statusBox)
        add(gap())
        add(credentialsRow)
        add(gap())
        add(JBLabel("Set up an agent").apply { alignmentX = Component.LEFT_ALIGNMENT; font = font.deriveFont(java.awt.Font.BOLD) })
        agentSnippets("", "").forEach { snippet ->
            val area = JTextArea(4, 60).apply {
                font = AdbToolboxTheme.Typography.mono
                isEditable = false
                lineWrap = true
            }
            val target = JBLabel(snippet.target).apply { foreground = AdbToolboxTheme.Colors.textDim }
            snippetAreas += area to target
            snippetTabs.addTab(snippet.agent, JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                border = JBUI.Borders.empty(6)
                add(target, BorderLayout.NORTH)
                add(JBScrollPane(area), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                    add(JButton("Copy").apply { addActionListener { copy(currentSnippets()[snippetTabs.selectedIndex].text) } })
                }, BorderLayout.SOUTH)
            })
        }
        snippetTabs.alignmentX = Component.LEFT_ALIGNMENT
        snippetTabs.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(190))
        add(snippetTabs)
        add(comment("Copy inserts the real token. Check the agent’s docs if its config format has changed."))
        add(gap())
        add(JBLabel("Exposed tools").apply { alignmentX = Component.LEFT_ALIGNMENT; font = font.deriveFont(java.awt.Font.BOLD) })
        tools.forEach { tool ->
            toolsModel.addRow(
                arrayOf(
                    tool.name,
                    tool.description.substringBefore(". ") + if (tool.destructive) " — asks first" else "",
                    if (tool.readOnly) "Read" else "Full",
                ),
            )
        }
        add(JBScrollPane(JBTable(toolsModel).apply { setShowGrid(false) }).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(200))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))
        })
        add(comment(
            "Uninstall and Clear data always open the same confirmation as in the tool window, naming the agent — also in Full control. " +
                "Cancel, or no answer in 60 s, returns “declined by user”. Shared prefs and databases are readable only for debuggable apps (run-as) or rooted devices.",
        ))
        addHierarchyListener {
            if (isShowing) {
                refresh()
                ticker.start()
            } else {
                ticker.stop()
            }
        }
    }

    var access: McpAccess
        get() = when {
            full.isSelected -> McpAccess.FullControl
            readOnly.isSelected -> McpAccess.ReadOnly
            else -> McpAccess.Off
        }
        set(value) {
            when (value) {
                McpAccess.Off -> off.isSelected = true
                McpAccess.ReadOnly -> readOnly.isSelected = true
                McpAccess.FullControl -> full.isSelected = true
            }
            // Not while IntelliJ only indexes the page: the token comes from the password safe.
            if (isShowing) refresh()
        }

    private fun url(port: Int) = "http://127.0.0.1:$port/mcp"

    private fun currentSnippets(): List<AgentSnippet> =
        agentSnippets(status().port?.let(::url) ?: "http://127.0.0.1:<port>/mcp", token())

    fun refresh() {
        val current = status()
        val running = current.running
        statusBox.fill = if (running) AdbToolboxTheme.Colors.brandBg else null
        statusBox.outline = if (running) AdbToolboxTheme.Colors.brandBorder else AdbToolboxTheme.Colors.border
        statusTitle.text = when {
            current.error != null -> "Couldn’t start: ${current.error}"
            running -> "Listening on ${url(current.port!!)} · ${current.sessions.size} agent${if (current.sessions.size == 1) "" else "s"} connected"
            else -> "Server stopped"
        }
        statusDetail.text = when {
            running -> current.sessions.firstOrNull()?.let { s ->
                val ago = s.lastCallAtMillis?.let { (System.currentTimeMillis() - it) / 1000 }
                "${s.clientName} · last call ${s.lastCall ?: "—"}${ago?.let { " $it s ago" } ?: ""}"
            } ?: "Waiting for an agent."
            access == McpAccess.Off -> "Choose Read only or Full control to start it. Nothing listens while it is off."
            else -> "Apply to start it."
        }
        copyUrl.isEnabled = running
        portLabel.text = current.port?.toString() ?: (AdbToolboxAppSettings.getInstance().mcpPort.takeIf { it > 0 }?.toString() ?: "picked on first start")
        tokenLabel.text = maskToken(token())
        val dimmed = access == McpAccess.Off
        listOf(copyToken, regenerateToken).forEach { it.isEnabled = !dimmed }
        currentSnippets().forEachIndexed { index, snippet ->
            val (area, target) = snippetAreas[index]
            area.text = snippet.text.replace(token(), maskToken(token()))
            target.text = snippet.target.replace(token(), maskToken(token()))
        }
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun radio(button: JBRadioButton, description: String) = JPanel(BorderLayout()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(button, BorderLayout.NORTH)
        add(JBLabel(description).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(24)
        }, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun comment(text: String) = JBLabel("<html>$text</html>").apply {
        alignmentX = Component.LEFT_ALIGNMENT
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(4, 0)
    }

    private fun gap() = javax.swing.Box.createVerticalStrut(JBUI.scale(8))
}
