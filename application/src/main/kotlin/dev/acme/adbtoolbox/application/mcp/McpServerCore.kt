package dev.acme.adbtoolbox.application.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException

/** One connected agent, as the status-bar chip and Settings show it (design §8, §11). */
data class McpSession(val id: String, val clientName: String, val lastCall: String? = null, val lastCallAtMillis: Long? = null)

/** What the HTTP layer sends back: a JSON body, or nothing (202) for notifications only. */
data class McpReply(val body: String?, val sessionId: String? = null)

/**
 * The transport-independent MCP server (ADR 0015): JSON-RPC 2.0 over the methods `initialize`,
 * `ping`, `tools/list` and `tools/call` plus notifications, with protocol-version negotiation and
 * the access level checked on every call. Sessions are created at `initialize`.
 */
class McpServerCore(
    private val tools: List<McpTool>,
    private val access: () -> McpAccess,
    private val serverVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val newSessionId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, McpSession>()

    /** Sessions with their last call, newest first. */
    fun sessions(): List<McpSession> = sessions.values.sortedByDescending { it.lastCallAtMillis ?: 0 }

    fun endSession(id: String) {
        sessions.remove(id)
    }

    fun endAllSessions() = sessions.clear()

    /** Tools an agent sees at [level]: everything in Full control, read tools in Read only, none when Off. */
    fun visibleTools(level: McpAccess = access()): List<McpTool> = when (level) {
        McpAccess.Off -> emptyList()
        McpAccess.ReadOnly -> tools.filter { it.readOnly }
        McpAccess.FullControl -> tools
    }

    suspend fun handle(body: String, sessionId: String?): McpReply {
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (error: Exception) {
            return McpReply(error(JsonNull, PARSE_ERROR, "Parse error: ${error.message}").toString())
        }
        return when (parsed) {
            is JsonArray -> {
                var created: String? = null
                val replies = parsed.mapNotNull { element ->
                    handleOne(element, sessionId).also { (_, id) -> if (id != null) created = id }.first
                }
                McpReply(if (replies.isEmpty()) null else JsonArray(replies).toString(), created)
            }
            else -> handleOne(parsed, sessionId).let { (reply, created) -> McpReply(reply?.toString(), created) }
        }
    }

    /** The reply (null for a notification) and the session created by `initialize`. */
    private suspend fun handleOne(element: JsonElement, sessionId: String?): Pair<JsonObject?, String?> {
        val message = element as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid request") to null
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
            ?: return (if (id == null) null else error(id, INVALID_REQUEST, "Missing method")) to null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (id == null) return null to null // notifications/initialized, notifications/cancelled, …
        return try {
            when (method) {
                "initialize" -> initialize(id, params)
                "ping" -> result(id, JsonObject(emptyMap())) to null
                "tools/list" -> result(id, buildJsonObject { put("tools", toolList()) }) to null
                "tools/call" -> callTool(id, params, sessionId) to null
                else -> error(id, METHOD_NOT_FOUND, "Method not found: $method") to null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error(id, INTERNAL_ERROR, failure.message ?: failure.toString()) to null
        }
    }

    private fun initialize(id: JsonElement, params: JsonObject): Pair<JsonObject, String> {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.last()
        val clientName = (params["clientInfo"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: "Agent"
        val session = McpSession(newSessionId(), clientName.toDisplayName())
        sessions[session.id] = session
        val result = buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "adb-toolbox")
                put("title", "ADB Toolbox")
                put("version", serverVersion)
            }
            put(
                "instructions",
                "Drives the Android device selected in ADB Toolbox (Android Studio / IntelliJ). Positions are in dp. " +
                    "Call get_ui before tapping; pass return_ui=true to actions to get the next screen in the same call; " +
                    "use screenshot annotate=true and tap mark=N where the UI tree does not show an element; pass serial to target another device.",
            )
        }
        return result(id, result) to session.id
    }

    private fun toolList(): JsonArray = buildJsonArray {
        visibleTools().forEach { tool ->
            add(
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                    putJsonObject("annotations") {
                        put("readOnlyHint", tool.readOnly)
                        put("destructiveHint", tool.destructive)
                        put("openWorldHint", false)
                    }
                },
            )
        }
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject, sessionId: String?): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: return error(id, INVALID_PARAMS, "Missing tool name")
        val tool = tools.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        if (tool !in visibleTools()) {
            val reason = if (access() == McpAccess.Off) "ADB Toolbox: access is Off" else "ADB Toolbox: $name needs Full control"
            return result(id, McpToolResult.error(reason).toJson())
        }
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val session = sessionId?.let(sessions::get)
        val clientName = session?.clientName ?: "Agent"
        val outcome = try {
            tool.call(arguments, McpCallContext(sessionId, clientName))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (bad: McpArgumentException) {
            McpToolResult.error("Invalid arguments: ${bad.message}")
        } catch (failure: Exception) {
            McpToolResult.error(failure.message ?: failure.toString())
        }
        if (session != null) sessions[session.id] = session.copy(lastCall = describe(name, arguments), lastCallAtMillis = now())
        return result(id, outcome.toJson())
    }

    private fun describe(name: String, arguments: JsonObject): String {
        val args = arguments.entries.joinToString(", ") { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            if (key in setOf("x", "y", "ref", "text", "package", "key")) text.take(24) else "$key=${text.take(16)}"
        }
        return "$name($args)"
    }

    private fun McpToolResult.toJson(): JsonObject = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                content.forEach { item ->
                    add(
                        when (item) {
                            is McpContent.Text -> buildJsonObject {
                                put("type", "text")
                                put("text", item.text)
                            }
                            is McpContent.Image -> buildJsonObject {
                                put("type", "image")
                                put("data", item.base64)
                                put("mimeType", item.mimeType)
                            }
                        },
                    )
                }
            },
        )
        put("isError", isError)
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    /** "claude-code" → "Claude Code", as the status-bar chip names the agent. */
    private fun String.toDisplayName(): String = KNOWN_CLIENTS[lowercase()]
        ?: split('-', '_', ' ').filter(String::isNotEmpty).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-03-26", "2025-06-18", "2025-11-25")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        private val KNOWN_CLIENTS = mapOf(
            "claude-code" to "Claude Code",
            "codex-mcp-client" to "Codex",
            "codex" to "Codex",
            "cursor-vscode" to "Cursor",
            "gemini-cli-mcp-client" to "Gemini CLI",
        )
    }
}

/** Reads a required argument of a tool call. */
fun JsonObject.requireString(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw McpArgumentException("'$name' is required")

fun JsonObject.optionalString(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.optionalDouble(name: String): Double? = (this[name] as? JsonPrimitive)?.let {
    it.contentOrNull?.toDoubleOrNull() ?: throw McpArgumentException("'$name' must be a number")
}

fun JsonObject.optionalBoolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.contentOrNull?.let {
    it.toBooleanStrictOrNull() ?: throw McpArgumentException("'$name' must be true or false")
}

