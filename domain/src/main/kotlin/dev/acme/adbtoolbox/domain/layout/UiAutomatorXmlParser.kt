package dev.acme.adbtoolbox.domain.layout

/** Result of parsing a `uiautomator dump`: the root node, or why the text is not a hierarchy. */
sealed interface UiTreeParse {
    data class Parsed(val root: UiNode) : UiTreeParse

    data class Malformed(val reason: String) : UiTreeParse
}

/**
 * Parses the XML `uiautomator dump` writes (ADR 0014) — `<hierarchy>` with nested `<node …>`
 * elements whose attributes carry everything. Hand-written for that flat dialect so `:domain` stays
 * free of JVM XML APIs: tags, double-quoted attributes and the five named plus numeric entities.
 * Several top-level windows are wrapped in a synthetic root spanning them.
 */
object UiAutomatorXmlParser {

    fun parse(xml: String): UiTreeParse {
        val start = xml.indexOf("<hierarchy")
        if (start < 0) return UiTreeParse.Malformed("no <hierarchy> element")
        val builders = ArrayDeque<NodeBuilder>()
        val topLevel = mutableListOf<UiNode>()
        var nextId = 0
        var index = start
        while (true) {
            val open = xml.indexOf('<', index)
            if (open < 0) break
            val close = tagEnd(xml, open) ?: return UiTreeParse.Malformed("unterminated tag at $open")
            val tag = xml.substring(open + 1, close)
            index = close + 1
            when {
                tag.startsWith("node") && (tag.length == 4 || tag[4].isWhitespace() || tag[4] == '/') -> {
                    val builder = NodeBuilder(nextId++, attributes(tag))
                    if (tag.endsWith("/")) attach(builder.build(), builders, topLevel) else builders.addLast(builder)
                }
                tag == "/node" -> {
                    val builder = builders.removeLastOrNull() ?: return UiTreeParse.Malformed("</node> without <node>")
                    attach(builder.build(), builders, topLevel)
                }
                tag.startsWith("/hierarchy") -> break
            }
        }
        if (builders.isNotEmpty()) return UiTreeParse.Malformed("${builders.size} <node> element(s) never closed")
        val root = when (topLevel.size) {
            0 -> return UiTreeParse.Malformed("the hierarchy has no nodes")
            1 -> topLevel.single()
            else -> UiNode(
                id = nextId,
                bounds = PixelRect(
                    topLevel.minOf { it.bounds.left }, topLevel.minOf { it.bounds.top },
                    topLevel.maxOf { it.bounds.right }, topLevel.maxOf { it.bounds.bottom },
                ),
                children = topLevel,
            )
        }
        return UiTreeParse.Parsed(root)
    }

    private fun attach(node: UiNode, builders: ArrayDeque<NodeBuilder>, topLevel: MutableList<UiNode>) {
        builders.lastOrNull()?.children?.add(node) ?: topLevel.add(node)
    }

    /** Index of the `>` closing the tag that starts at [open], skipping `>` inside quoted values. */
    private fun tagEnd(xml: String, open: Int): Int? {
        var quoted = false
        for (i in open + 1 until xml.length) {
            when (xml[i]) {
                '"' -> quoted = !quoted
                '>' -> if (!quoted) return i
            }
        }
        return null
    }

    private val ATTRIBUTE = Regex("""([\w:.-]+)="([^"]*)"""")

    private fun attributes(tag: String): Map<String, String> =
        ATTRIBUTE.findAll(tag).associate { it.groupValues[1] to decodeEntities(it.groupValues[2]) }

    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        return ENTITY.replace(value) { match ->
            when (val name = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (name.startsWith("#x")) name.drop(2).toInt(16) else name.drop(1).toInt()
                    codePointToString(code)
                }
            }
        }
    }

    private fun codePointToString(code: Int): String = if (code < 0x10000) {
        code.toChar().toString()
    } else {
        val offset = code - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }

    private val NUMBER = Regex("-?\\d+")

    /** `[l,t][r,b]`; anything else is an empty rectangle. */
    private fun bounds(text: String?): PixelRect {
        val numbers = NUMBER.findAll(text.orEmpty()).map { it.value.toInt() }.toList()
        return if (numbers.size == 4) PixelRect(numbers[0], numbers[1], numbers[2], numbers[3]) else PixelRect.EMPTY
    }

    private class NodeBuilder(val id: Int, val attributes: Map<String, String>) {
        val children = mutableListOf<UiNode>()

        fun build(): UiNode {
            fun flag(name: String) = attributes[name] == "true"
            return UiNode(
                id = id,
                className = attributes["class"].orEmpty(),
                resourceId = attributes["resource-id"].orEmpty(),
                text = attributes["text"].orEmpty(),
                contentDescription = attributes["content-desc"].orEmpty(),
                packageName = attributes["package"].orEmpty(),
                bounds = bounds(attributes["bounds"]),
                flags = UiNodeFlags(
                    clickable = flag("clickable"),
                    longClickable = flag("long-clickable"),
                    focusable = flag("focusable"),
                    enabled = attributes["enabled"] != "false",
                    checkable = flag("checkable"),
                    checked = flag("checked"),
                    selected = flag("selected"),
                    scrollable = flag("scrollable"),
                    password = flag("password"),
                    naf = flag("NAF"),
                ),
                children = children.toList(),
            )
        }
    }
}
