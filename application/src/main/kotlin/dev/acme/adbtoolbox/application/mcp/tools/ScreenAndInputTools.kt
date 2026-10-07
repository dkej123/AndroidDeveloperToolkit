package dev.acme.adbtoolbox.application.mcp.tools

import dev.acme.adbtoolbox.application.layout.LayoutCapture
import dev.acme.adbtoolbox.application.mcp.McpArgumentException
import dev.acme.adbtoolbox.application.mcp.McpCallContext
import dev.acme.adbtoolbox.application.mcp.McpContent
import dev.acme.adbtoolbox.application.mcp.McpTool
import dev.acme.adbtoolbox.application.mcp.McpToolResult
import dev.acme.adbtoolbox.application.mcp.optionalBoolean
import dev.acme.adbtoolbox.application.mcp.optionalDouble
import dev.acme.adbtoolbox.application.mcp.optionalString
import dev.acme.adbtoolbox.application.mcp.requireString
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.input.InputCommands
import dev.acme.adbtoolbox.domain.input.SystemKey
import dev.acme.adbtoolbox.domain.layout.AccessibilityAudit
import dev.acme.adbtoolbox.domain.layout.UiHierarchy
import dev.acme.adbtoolbox.domain.layout.UiNode
import dev.acme.adbtoolbox.domain.layout.UiTreeText
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** A tool defined by its metadata and a block. */
class SimpleTool(
    override val name: String,
    override val description: String,
    override val readOnly: Boolean,
    override val inputSchema: JsonObject,
    override val destructive: Boolean = false,
    private val block: suspend (JsonObject, McpCallContext) -> McpToolResult,
) : McpTool {
    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult = try {
        block(arguments, context)
    } catch (failure: McpToolFailure) {
        McpToolResult.error(failure.message.orEmpty())
    } catch (bad: McpArgumentException) {
        McpToolResult.error("Invalid arguments: ${bad.message}")
    }
}

/**
 * See and Act tools: screenshot, UI tree, accessibility audit, tap, swipe, typing and keys
 * (task 065). Positions are dp, like layout code; the device works in pixels.
 *
 * Ported from Oh My Android, MIT — `Sources/MCP/Tools/ScreenTools.swift`, `InputTools.swift`.
 */
@OptIn(ExperimentalEncodingApi::class)
fun screenAndInputTools(env: McpToolEnvironment): List<McpTool> = listOf(
    SimpleTool(
        "screenshot",
        "Screenshot of the device screen, scaled so 1 px = 1 dp — the coordinates get_ui and tap use. " +
            "Prefer get_ui to read text or find elements; use this to check visuals.",
        readOnly = true,
        inputSchema = schema(SERIAL),
    ) { args, _ ->
        val serial = env.device(args)
        when (val snapshot = env.layout.snapshot(serial)) {
            is LayoutCapture.Failed -> McpToolResult.error(snapshot.reason)
            is LayoutCapture.Captured -> {
                val h = snapshot.value.hierarchy
                env.remember(serial, h)
                val width = h.dp(h.root.bounds.width).roundToInt()
                val height = h.dp(h.root.bounds.height).roundToInt()
                val png = env.imageScaler.scale(snapshot.value.png, width, height) ?: snapshot.value.png
                McpToolResult(listOf(McpContent.Image(Base64.encode(png)), McpContent.Text(UiTreeText.screenSummary(h))))
            }
        }
    },
    SimpleTool(
        "get_ui",
        "The screen's UI as compact text, one element per line: [ref] Class \"text\" #id @x,y WxH flags (dp). " +
            "Use the ref with tap and swipe. Works for Compose and Views.",
        readOnly = true,
        inputSchema = schema(
            SERIAL,
            bool("interactive_only", "Only elements you can tap, type into or scroll."),
            str("query", "Only elements whose text, description or id contains this."),
        ),
    ) { args, _ ->
        val serial = env.device(args)
        val h = env.hierarchy(serial)
        val query = args.optionalString("query")
        val body = if (query != null) {
            UiTreeText.matches(query, h).joinToString("\n") { UiTreeText.line(it, h) }.ifEmpty { "Nothing on screen contains \"$query\"." }
        } else {
            UiTreeText.tree(h, interactiveOnly = args.optionalBoolean("interactive_only") ?: false)
        }
        McpToolResult.text(UiTreeText.screenSummary(h) + "\n" + body)
    },
    SimpleTool(
        "accessibility_audit",
        "TalkBack order of the screen with what each stop announces and its problems: missing labels, " +
            "touch targets under 48 dp, images without a description. Markdown.",
        readOnly = true,
        inputSchema = schema(SERIAL),
    ) { args, _ ->
        val serial = env.device(args)
        McpToolResult.text(AccessibilityAudit.audit(env.hierarchy(serial)).markdown())
    },
    SimpleTool(
        "tap",
        "Tap an element by ref (from get_ui), by visible text, or at x,y in dp. Give one of the three. long=true long-presses.",
        readOnly = false,
        inputSchema = schema(SERIAL, int("ref", "Element ref from get_ui."), str("text", "Visible text or description."), num("x", "dp"), num("y", "dp"), bool("long", "Long-press.")),
    ) { args, _ ->
        val serial = env.device(args)
        val target = env.point(serial, args, allowText = true)
        val h = target.hierarchy
        val x = h.px(target.x).roundToInt()
        val y = h.px(target.y).roundToInt()
        val request = if (args.optionalBoolean("long") == true) InputCommands.longPress(serial, x, y) else InputCommands.tap(serial, x, y)
        env.transport.executeText(request)
        McpToolResult.text("Tapped ${target.label} at ${target.x.roundToInt()},${target.y.roundToInt()} dp.")
    },
    SimpleTool(
        "swipe",
        "Swipe or scroll: direction up/down/left/right over an element (ref) or the screen centre, or from x,y to x2,y2 in dp. " +
            "\"up\" moves content up (scrolls down the page).",
        readOnly = false,
        inputSchema = schema(
            SERIAL,
            str("direction", "Finger direction.", oneOf = listOf("up", "down", "left", "right")),
            int("ref", "Element to swipe over."),
            num("x", "Start x, dp"), num("y", "Start y, dp"), num("x2", "End x, dp"), num("y2", "End y, dp"),
            int("duration_ms", "Default 300."),
        ),
    ) { args, _ ->
        val serial = env.device(args)
        val duration = (args.optionalDouble("duration_ms") ?: 300.0).toInt().coerceIn(50, 5_000)
        val x2 = args.optionalDouble("x2")
        val y2 = args.optionalDouble("y2")
        val h: UiHierarchy
        val from: Pair<Double, Double>
        val to: Pair<Double, Double>
        if (x2 != null && y2 != null) {
            val start = env.point(serial, args, allowText = false)
            h = start.hierarchy
            from = start.x to start.y
            to = x2 to y2
        } else {
            val direction = args.optionalString("direction") ?: throw McpArgumentException("Give direction, or x,y and x2,y2.")
            val area = env.point(serial, args, allowText = false)
            h = area.hierarchy
            val reach = 0.35 * if (direction == "left" || direction == "right") area.width else area.height
            from = area.x to area.y
            to = when (direction) {
                "up" -> area.x to area.y - reach
                "down" -> area.x to area.y + reach
                "left" -> area.x - reach to area.y
                "right" -> area.x + reach to area.y
                else -> throw McpArgumentException("direction must be up, down, left or right")
            }
        }
        fun px(v: Double) = h.px(v).roundToInt()
        env.transport.executeText(InputCommands.swipe(serial, px(from.first), px(from.second), px(to.first), px(to.second), duration))
        McpToolResult.text("Swiped from ${from.first.roundToInt()},${from.second.roundToInt()} to ${to.first.roundToInt()},${to.second.roundToInt()} dp.")
    },
    SimpleTool(
        "type_text",
        "Type into the focused field (tap it first). Printable ASCII on one line only, an adb limit.",
        readOnly = false,
        inputSchema = schema(SERIAL, str("text", "Text to type.", required = true), bool("submit", "Press Enter after typing.")),
    ) { args, _ ->
        val serial = env.device(args)
        val text = args.requireString("text")
        if (!InputCommands.canType(text)) throw McpArgumentException("adb can type printable ASCII only, on one line. Use submit for Enter.")
        env.transport.executeText(InputCommands.text(serial, text))
        if (args.optionalBoolean("submit") == true) env.transport.executeText(InputCommands.key(serial, SystemKey.Enter))
        McpToolResult.text("Typed ${text.length} characters.")
    },
    SimpleTool(
        "press_key",
        "Press a system key. escape also hides the keyboard; delete is backspace.",
        readOnly = false,
        inputSchema = schema(SERIAL, str("key", "Key.", required = true, oneOf = SystemKey.entries.map { it.id }), int("times", "Repeat count, 1–50. Default 1.")),
    ) { args, _ ->
        val serial = env.device(args)
        val key = SystemKey.of(args.requireString("key")) ?: throw McpArgumentException("unknown key")
        val times = (args.optionalDouble("times") ?: 1.0).toInt().coerceIn(1, 50)
        repeat(times) { env.transport.executeText(InputCommands.key(serial, key)) }
        McpToolResult.text("Pressed ${key.id}${if (times > 1) " $times times" else ""}.")
    },
)

/** Where a gesture lands, in dp, with the element it belongs to. */
internal data class GestureTarget(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val label: String,
    val hierarchy: UiHierarchy,
)

/** Resolves `ref`, `text` or `x`/`y` to a point in dp; with none (and no text allowed), the screen centre. */
internal suspend fun McpToolEnvironment.point(serial: DeviceSerial, args: JsonObject, allowText: Boolean): GestureTarget {
    args.optionalDouble("ref")?.let { ref ->
        val h = lastHierarchy(serial) ?: hierarchy(serial)
        val node = h.node(ref.toInt()) ?: throw McpArgumentException("ref ${ref.toInt()} is not on the last get_ui; call get_ui again.")
        return target(node, h, UiTreeText.line(node, h))
    }
    if (allowText) {
        args.optionalString("text")?.let { text ->
            val h = hierarchy(serial)
            val (node, count) = UiTreeText.target(text, h) ?: throw McpToolFailure("No element shows \"$text\". Call get_ui to see the screen.")
            return target(node, h, UiTreeText.line(node, h) + if (count > 1) " (first of $count matches)" else "")
        }
    }
    val h = lastHierarchy(serial) ?: hierarchy(serial)
    val width = h.dp(h.root.bounds.width)
    val height = h.dp(h.root.bounds.height)
    val x = args.optionalDouble("x")
    val y = args.optionalDouble("y")
    return when {
        x != null && y != null -> GestureTarget(x, y, width, height, "point", h)
        x != null || y != null -> throw McpArgumentException("Give both x and y.")
        allowText -> throw McpArgumentException("Give ref, text, or x and y.")
        else -> GestureTarget(width / 2, height / 2, width, height, "screen", h)
    }
}

private fun target(node: UiNode, h: UiHierarchy, label: String): GestureTarget {
    val b = node.bounds
    return GestureTarget(h.dp(b.centerX), h.dp(b.centerY), h.dp(b.width), h.dp(b.height), label, h)
}
