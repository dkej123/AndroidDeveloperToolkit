package io.github.dkej123.devicecockpit.application.mcp

import kotlinx.serialization.json.JsonObject

/** Who may call what (ADR 0015). Off: no server. Read only: only read tools are listed or callable. */
enum class McpAccess { Off, ReadOnly, FullControl }

/** One item of a tool result: text, or an image as base64. */
sealed interface McpContent {
    data class Text(val text: String) : McpContent

    data class Image(val base64: String, val mimeType: String = "image/png") : McpContent
}

/** A tool's answer; [isError] results are tool failures the model sees, not protocol errors. */
data class McpToolResult(val content: List<McpContent>, val isError: Boolean = false) {
    companion object {
        fun text(text: String) = McpToolResult(listOf(McpContent.Text(text)))

        fun error(text: String) = McpToolResult(listOf(McpContent.Text(text)), isError = true)
    }
}

/** What a call knows about its caller. */
data class McpCallContext(val sessionId: String?, val clientName: String)

/** A tool argument a call got wrong; reported to the model as a tool error naming the problem. */
class McpArgumentException(message: String) : IllegalArgumentException(message)

/**
 * One MCP tool (ADR 0015, task 065). [readOnly] tools are the only ones listed in Read only;
 * [destructive] ones are annotated so agents ask first (Uninstall and Clear data also confirm in the IDE).
 */
interface McpTool {
    val name: String
    val description: String

    /** JSON Schema of the arguments (`type: object`). */
    val inputSchema: JsonObject
    val readOnly: Boolean
    val destructive: Boolean get() = false

    suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult
}
