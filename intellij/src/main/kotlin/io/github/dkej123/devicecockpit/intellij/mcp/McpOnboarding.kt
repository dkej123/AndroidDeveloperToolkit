package io.github.dkej123.devicecockpit.intellij.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import io.github.dkej123.devicecockpit.adapters.jvm.process.JvmProcessExecutor
import io.github.dkej123.devicecockpit.application.mcp.ClaudeCodeConnection
import io.github.dkej123.devicecockpit.application.mcp.ClaudeCodeConnector
import io.github.dkej123.devicecockpit.application.mcp.MCP_SERVER_NAME
import io.github.dkej123.devicecockpit.application.mcp.McpAccess
import io.github.dkej123.devicecockpit.application.mcp.findClaudeCli
import io.github.dkej123.devicecockpit.intellij.composition.DeviceCockpitProjectService

/** The Claude Code connector with the CLI looked up on this machine. */
fun defaultClaudeCodeConnector(): ClaudeCodeConnector = ClaudeCodeConnector(JvmProcessExecutor()) {
    findClaudeCli(System.getProperty("user.home"), System.getenv("PATH"), com.intellij.openapi.util.SystemInfo.isWindows) { java.io.File(it).canExecute() }
}

/**
 * The tool window tip's one click: turns the MCP server on with Full control when it is off (the
 * destructive tools still ask in the IDE), registers it with Claude Code and installs the skill.
 * Blocking; call it off the EDT. Returns the line the tip shows.
 */
object McpOnboarding {
    suspend fun turnOnAndConnectClaudeCode(): String {
        val settings = DeviceCockpitAppSettings.getInstance()
        val server = McpServerService.getInstance()
        if (settings.mcpAccess == McpAccess.Off) {
            settings.mcpAccess = McpAccess.FullControl
            ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach {
                it.service<DeviceCockpitProjectService>().registerMcpTools()
            }
            server.applyAccess()
        }
        val status = server.status.value
        val port = status.port ?: return "The MCP server didn’t start: ${status.error ?: "unknown error"}. See Settings › AI agents."
        val connected = defaultClaudeCodeConnector().connect("http://127.0.0.1:$port/mcp", settings.mcpToken())
        val skill = connected == ClaudeCodeConnection.Connected &&
            runCatching { AgentSkillInstaller.forUser().install(SkillAgent.ClaudeCode) }.isSuccess
        return when (connected) {
            ClaudeCodeConnection.Connected ->
                "MCP server on (Full control) and Claude Code connected as $MCP_SERVER_NAME" + (if (skill) " with the skill" else "") +
                    ". Start a new Claude Code session to use it."
            ClaudeCodeConnection.CliNotFound ->
                "MCP server on (Full control), but Claude Code (claude) wasn’t found — set up your agent in Settings › AI agents."
            is ClaudeCodeConnection.Failed -> "MCP server on, but connecting Claude Code failed: ${connected.reason.lineSequence().first()}"
        }
    }
}
