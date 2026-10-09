package io.github.dkej123.devicecockpit.intellij.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.mcp.McpAccess
import io.github.dkej123.devicecockpit.application.mcp.McpCallContext
import io.github.dkej123.devicecockpit.application.mcp.McpTool
import io.github.dkej123.devicecockpit.application.mcp.McpToolResult
import io.github.dkej123.devicecockpit.intellij.feedback.FeedbackStatusPanel
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class EchoTool(override val name: String, override val readOnly: Boolean) : McpTool {
    override val description = "$name tool"
    override val inputSchema = buildJsonObject { put("type", "object") }
    override suspend fun call(arguments: JsonObject, context: McpCallContext) = McpToolResult.text("$name by ${context.clientName}")
}

class McpServerServiceTest : BasePlatformTestCase() {
    private val settings get() = AdbToolboxAppSettings.getInstance()
    private val service get() = McpServerService.getInstance()

    override fun tearDown() {
        try {
            settings.mcpAccess = McpAccess.Off
            service.unregister(project)
            service.applyAccess()
        } finally {
            super.tearDown()
        }
    }

    private fun post(port: Int, body: String, token: String?, session: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).POST(HttpRequest.BodyPublishers.ofString(body))
        token?.let { request.header("Authorization", "Bearer $it") }
        session?.let { request.header("Mcp-Session-Id", it) }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun `test the server follows the access level and serves the project's tools with the token`() {
        settings.mcpPort = 0
        service.register(project, listOf(EchoTool("get_ui", readOnly = true), EchoTool("tap", readOnly = false)))
        assertFalse(service.status.value.running) // Off by default

        settings.mcpAccess = McpAccess.ReadOnly
        service.applyAccess()
        val port = service.status.value.port!!
        assertEquals(port, settings.mcpPort) // kept for the setup snippets

        assertEquals(401, post(port, "{}", token = "wrong").statusCode())
        val token = settings.mcpToken()
        val init = post(port, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"claude-code"}}}""", token)
        val session = init.headers().firstValue("Mcp-Session-Id").get()
        val list = post(port, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", token, session).body()
        assertTrue(list.contains("get_ui"))
        assertFalse(list.contains("\"tap\""))
        val call = post(port, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_ui"}}""", token, session).body()
        assertTrue(call.contains("get_ui by Claude Code"))
        assertEquals("Claude Code", service.status.value.sessions.single().clientName)

        settings.mcpAccess = McpAccess.Off
        service.applyAccess()
        assertFalse(service.status.value.running)
    }

    fun `test regenerating the token locks out the old one`() {
        val old = settings.mcpToken()
        val fresh = service.regenerateToken()
        assertFalse(old == fresh)
        assertTrue(fresh.startsWith("atk_"))
        assertEquals("atk_••••${fresh.takeLast(4)}", maskToken(fresh))
    }

    fun `test setup snippets carry the url and the real token`() {
        val snippets = agentSnippets("http://127.0.0.1:47123/mcp", "atk_secret")
        assertEquals(listOf("Claude Code", "Codex CLI", "Cursor", "Gemini CLI", "Other"), snippets.map { it.agent })
        assertEquals(
            "claude mcp add --transport http --scope user adb-toolbox http://127.0.0.1:47123/mcp --header \"Authorization: Bearer atk_secret\"",
            snippets.first().text,
        )
        assertTrue(snippets.first { it.agent == "Gemini CLI" }.text.contains("\"httpUrl\": \"http://127.0.0.1:47123/mcp\""))
    }

    fun `test the status bar chip shows the agent and its last call`() {
        val opened = mutableListOf<Unit>()
        val panel = FeedbackStatusPanel(onOpenMcpSettings = { opened += Unit })
        panel.updateMcp(agent = null, access = "Read only", inFlight = false, lastCall = null)
        assertFalse(panel.mcpChipForTest.isVisible)

        panel.updateMcp(agent = "Claude Code", access = "Full control", inFlight = true, lastCall = "tap(540, 1210)")
        assertTrue(panel.mcpChipForTest.isVisible)
        assertEquals("MCP · Claude Code", panel.mcpChipForTest.text)
        assertTrue(panel.mcpChipForTest.toolTipText.contains("Full control"))
        panel.mcpChipForTest.doClick()
        assertEquals(1, opened.size)
    }
}
