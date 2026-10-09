package io.github.dkej123.devicecockpit.domain.layout

import kotlin.math.roundToInt

/**
 * Compact text form of a UI tree, written for a model to read (MCP `get_ui`): one line per useful
 * node, in dp.
 *
 *     [23] Button "Sign in" #sign_in @16,700 379x48 tap
 *
 * `[23]` is the ref for `tap` and `swipe`, `#` the resource id, `@x,y` the top-left corner and `WxH`
 * the size. Layout-only wrappers are left out and their children move up.
 *
 * Ported from Oh My Android, MIT — `Sources/MCP/Format/UITreeText.swift`.
 */
object UiTreeText {
    const val MAX_LINES = 400
    private const val MAX_TEXT = 80

    fun tree(hierarchy: UiHierarchy, interactiveOnly: Boolean = false): String {
        val lines = mutableListOf<String>()
        var omitted = 0
        fun visit(node: UiNode, depth: Int) {
            if (!isVisible(node, hierarchy)) return
            val shown = if (interactiveOnly) isInteractive(node) else isUseful(node)
            if (shown) {
                if (lines.size < MAX_LINES) lines += " ".repeat(if (interactiveOnly) 0 else depth) + line(node, hierarchy) else omitted++
            }
            node.children.forEach { visit(it, if (shown) depth + 1 else depth) }
        }
        visit(hierarchy.root, 0)
        if (lines.isEmpty()) lines += if (interactiveOnly) "No interactive elements on screen." else "Screen has no readable elements."
        if (omitted > 0) lines += "… $omitted more. Narrow with query or interactive_only."
        return lines.joinToString("\n")
    }

    /** Visible nodes you can tap, type into or scroll, in tree order — what `get_ui interactive_only` lists. */
    fun interactive(hierarchy: UiHierarchy): List<UiNode> = hierarchy.root.descendantsAndSelf()
        .filter { isVisible(it, hierarchy) && isInteractive(it) }
        .toList()

    /** Visible nodes whose text, description or resource id contains [query], ignoring case. */
    fun matches(query: String, hierarchy: UiHierarchy): List<UiNode> = hierarchy.root.descendantsAndSelf()
        .filter { node -> isVisible(node, hierarchy) && listOf(node.text, node.contentDescription, node.resourceId).any { it.contains(query, ignoreCase = true) } }
        .toList()

    /** Best node for a text target: exact text or description first, then contains; with how many matched. */
    fun target(text: String, hierarchy: UiHierarchy): Pair<UiNode, Int>? {
        val candidates = matches(text, hierarchy)
        val exact = candidates.filter { it.text.equals(text, ignoreCase = true) || it.contentDescription.equals(text, ignoreCase = true) }
        val pool = exact.ifEmpty { candidates }
        return pool.firstOrNull()?.let { it to pool.size }
    }

    fun line(node: UiNode, hierarchy: UiHierarchy): String = buildList {
        add("[${node.id}]")
        node.shortClassName.takeIf { it.isNotEmpty() && it != "View" }?.let(::add)
        if (node.text.isNotEmpty()) add(quoted(node.text))
        if (node.contentDescription.isNotEmpty() && node.contentDescription != node.text) add("desc=" + quoted(node.contentDescription))
        if (node.resourceId.isNotEmpty()) add("#" + node.shortResourceId)
        add(frame(node.bounds, hierarchy))
        val f = node.flags
        if (f.clickable) add("tap")
        if (f.longClickable) add("long")
        if (f.checkable) add(if (f.checked) "checked" else "unchecked")
        if (f.scrollable) add("scroll")
        if (f.selected) add("selected")
        if (f.password) add("password")
        if (!f.enabled) add("disabled")
    }.joinToString(" ")

    /** "@x,y WxH" in dp. */
    fun frame(rect: PixelRect, hierarchy: UiHierarchy): String {
        fun dp(px: Int) = hierarchy.dp(px).roundToInt()
        return "@${dp(rect.left)},${dp(rect.top)} ${dp(rect.width)}x${dp(rect.height)}"
    }

    fun screenSummary(hierarchy: UiHierarchy): String =
        "Screen ${hierarchy.dp(hierarchy.root.bounds.width).roundToInt()}x${hierarchy.dp(hierarchy.root.bounds.height).roundToInt()} dp, ${hierarchy.densityDpi} dpi"

    private fun quoted(text: String): String {
        val flat = text.replace('\n', ' ')
        val short = if (flat.length > MAX_TEXT) flat.take(MAX_TEXT) + "…" else flat
        return "\"" + short.replace("\"", "\\\"") + "\""
    }

    private fun isVisible(node: UiNode, hierarchy: UiHierarchy): Boolean {
        val b = node.bounds
        val screen = hierarchy.root.bounds
        return !b.isEmpty && b.left < screen.right && b.right > screen.left && b.top < screen.bottom && b.bottom > screen.top
    }

    private fun isInteractive(node: UiNode): Boolean =
        node.flags.clickable || node.flags.longClickable || node.flags.checkable || node.flags.scrollable || isTextField(node)

    private fun isUseful(node: UiNode): Boolean =
        isInteractive(node) || node.text.isNotEmpty() || node.contentDescription.isNotEmpty() || node.resourceId.isNotEmpty()

    private fun isTextField(node: UiNode) = node.className.endsWith("EditText") || node.className.endsWith("AutoCompleteTextView")
}
