package dev.acme.adbtoolbox.application.mcp

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

private class FakeTool(
    override val name: String,
    override val readOnly: Boolean,
    override val destructive: Boolean = false,
    val answer: suspend (JsonObject, McpCallContext) -> McpToolResult = { _, _ -> McpToolResult.text("ok") },
) : McpTool {
    override val description = "$name tool"
    override val inputSchema = buildJsonObject { put("type", "object") }
    override suspend fun call(arguments: JsonObject, context: McpCallContext) = answer(arguments, context)
}

class McpServerCoreTest {

    private var level = McpAccess.FullControl
    private var clock = 1_000L
    private val screenshot = FakeTool("screenshot", readOnly = true)
    private val tap = FakeTool("tap", readOnly = false, answer = { args, ctx -> McpToolResult.text("tapped ${args["x"]} for ${ctx.clientName}") })
    private val uninstall = FakeTool("uninstall", readOnly = false, destructive = true)
    private val failing = FakeTool("broken", readOnly = true, answer = { _, _ -> error("device offline") })
    private val core = McpServerCore(listOf(screenshot, tap, uninstall, failing), { level }, "1.2.3", now = { clock }, newSessionId = { "s1" })

    private suspend fun call(body: String, session: String? = "s1"): JsonObject =
        Json.parseToJsonElement(core.handle(body, session).body!!).jsonObject

    private suspend fun initialize(client: String = "claude-code", version: String = "2025-06-18") =
        core.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","clientInfo":{"name":"$client","version":"2"}}}""", null)

    @Test
    fun `initialize negotiates the version, creates a session and names the agent`() = runTest {
        val reply = initialize()

        reply.sessionId shouldBe "s1"
        val result = Json.parseToJsonElement(reply.body!!).jsonObject["result"]!!.jsonObject
        result["protocolVersion"]!!.jsonPrimitive.content shouldBe "2025-06-18"
        result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content shouldBe "1.2.3"
        core.sessions().single().clientName shouldBe "Claude Code"
    }

    @Test
    fun `an unknown protocol version gets the newest supported one`() = runTest {
        val reply = initialize(version = "2099-01-01")
        Json.parseToJsonElement(reply.body!!).jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content shouldBe "2025-11-25"
    }

    @Test
    fun `tools are listed by access level with annotations`() = runTest {
        fun names(o: JsonObject) = o["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val list = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

        names(call(list)) shouldContainExactly listOf("screenshot", "tap", "uninstall", "broken")
        val annotations = call(list)["result"]!!.jsonObject["tools"]!!.jsonArray[2].jsonObject["annotations"]!!.jsonObject
        annotations["destructiveHint"]!!.jsonPrimitive.content shouldBe "true"
        level = McpAccess.ReadOnly
        names(call(list)) shouldContainExactly listOf("screenshot", "broken")
        level = McpAccess.Off
        names(call(list)) shouldBe emptyList()
    }

    @Test
    fun `a call runs the tool, records the last call and reports tool failures as results`() = runTest {
        initialize()
        clock = 5_000

        val result = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"tap","arguments":{"x":120,"y":40}}}""")["result"]!!.jsonObject

        result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe "tapped 120 for Claude Code"
        result["isError"]!!.jsonPrimitive.content shouldBe "false"
        core.sessions().single().lastCall shouldBe "tap(120, 40)"
        core.sessions().single().lastCallAtMillis shouldBe 5_000

        val broken = call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"broken"}}""")["result"]!!.jsonObject
        broken["isError"]!!.jsonPrimitive.content shouldBe "true"
        broken["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe "device offline"
    }

    @Test
    fun `read only refuses tools that change the device`() = runTest {
        level = McpAccess.ReadOnly

        val result = call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"tap"}}""")["result"]!!.jsonObject

        result["isError"]!!.jsonPrimitive.content shouldBe "true"
        result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldContain "needs Full control"
    }

    @Test
    fun `protocol errors use JSON-RPC codes`() = runTest {
        call("not json")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int shouldBe -32700
        call("""{"jsonrpc":"2.0","id":6,"method":"resources/list"}""")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int shouldBe -32601
        call("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nope"}}""")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int shouldBe -32602
    }

    @Test
    fun `notifications get no reply, batches get one reply per request`() = runTest {
        core.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", "s1").body shouldBe null

        val batch = Json.parseToJsonElement(
            core.handle("""[{"jsonrpc":"2.0","id":8,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""", "s1").body!!,
        ) as JsonArray
        batch.size shouldBe 1
        batch[0].jsonObject["id"]!!.jsonPrimitive.int shouldBe 8
    }

    @Test
    fun `invalid arguments are tool errors that name the problem`() = runTest {
        val strict = FakeTool("strict", readOnly = true, answer = { args, _ -> McpToolResult.text(args.requireString("package")) })
        val server = McpServerCore(listOf(strict), { McpAccess.ReadOnly }, "1")

        val reply = Json.parseToJsonElement(server.handle("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"strict","arguments":{}}}""", null).body!!)
        reply.jsonObject["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe "Invalid arguments: 'package' is required"
    }
}
