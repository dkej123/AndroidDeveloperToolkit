package dev.acme.adbtoolbox.domain.layout

import kotlin.math.abs
import kotlin.math.roundToInt

/** A problem the audit reports for a screen-reader stop. */
enum class AccessibilityIssueKind { NoLabel, NotAccessibilityFriendly, SmallTouchTarget }

data class AccessibilityIssue(val kind: AccessibilityIssueKind, val message: String)

/** One stop of the screen reader: its approximate TalkBack [order] (1-based), what it announces and its problems. */
data class AccessibilityStop(
    val order: Int,
    val node: UiNode,
    val role: String,
    val spoken: String,
    val issues: List<AccessibilityIssue>,
)

/**
 * The audit of one screen: the screen-reader [stops] in order, and the images TalkBack skips because
 * they have no description and are not part of a control — fine when decorative, a problem when they
 * carry meaning, so they are listed rather than flagged.
 */
data class AccessibilityReport(val stops: List<AccessibilityStop>, val unannouncedImages: List<UiNode>) {
    fun markdown(): String = buildString {
        val withProblems = stops.count { it.issues.isNotEmpty() }
        append("# Accessibility audit\n\n")
        append("${stops.size} stops, $withProblems with problems")
        if (unannouncedImages.isNotEmpty()) {
            append("; ${unannouncedImages.size} image${if (unannouncedImages.size == 1) "" else "s"} without a description")
        }
        append(".\n\n| # | Announced | Problems |\n|---|---|---|\n")
        stops.forEach { stop ->
            val problems = stop.issues.joinToString("; ") { it.message }.ifEmpty { "—" }
            append("| ${stop.order} | ${stop.spoken.cell()} | ${problems.cell()} |\n")
        }
        if (unannouncedImages.isNotEmpty()) {
            append("\n## Images TalkBack skips\n\n")
            append("Fine when decorative; give them a content description when they carry meaning.\n\n")
            unannouncedImages.forEach { image ->
                val b = image.bounds
                append("- ${image.label} at [${b.left},${b.top}][${b.right},${b.bottom}] px\n")
            }
        }
    }

    private fun String.cell() = replace("|", "\\|").replace("\n", " ")
}

/**
 * Approximates TalkBack's traversal: depth-first; an actionable node (clickable, focusable,
 * checkable, long-clickable — but not a merely focusable container of actionable nodes) is one stop and swallows its children, whose text is merged into the
 * announcement; a non-actionable node with text is a stop of its own; pure containers are skipped.
 * Siblings are read in rows top to bottom (centres within 16 dp share a row), then left to right.
 *
 * Ported from Oh My Android, MIT — `Sources/Core/Android/AccessibilityTraversal.swift`; the
 * image rule is reworked (their image check could never fire on a stop).
 */
object AccessibilityAudit {
    const val MINIMUM_TARGET_DP = 48.0
    private const val ROW_TOLERANCE_DP = 16.0

    fun audit(hierarchy: UiHierarchy): AccessibilityReport {
        val stops = mutableListOf<AccessibilityStop>()
        val images = mutableListOf<UiNode>()
        visit(hierarchy.root, hierarchy, hierarchy.root.bounds, stops, images)
        return AccessibilityReport(stops, images)
    }

    fun items(hierarchy: UiHierarchy): List<AccessibilityStop> = audit(hierarchy).stops

    /** `viewport` is the nearest scrolling container: nodes cut by its edges are only partly on screen. */
    private fun visit(
        node: UiNode,
        hierarchy: UiHierarchy,
        viewport: PixelRect,
        stops: MutableList<AccessibilityStop>,
        images: MutableList<UiNode>,
    ) {
        if (node.bounds.isEmpty) return
        if (isStop(node)) {
            stops += stop(node, mergedText(node), hierarchy, viewport, stops.size + 1)
            return
        }
        if (node.text.isNotEmpty() || node.contentDescription.isNotEmpty()) {
            stops += stop(node, ownText(node), hierarchy, viewport, stops.size + 1)
        } else if (role(node) == "Image") {
            images += node
        }
        val inner = if (node.flags.scrollable) node.bounds else viewport
        spatiallyOrdered(node.children, hierarchy).forEach { visit(it, hierarchy, inner, stops, images) }
    }

    /**
     * A node that is only focusable (Settings' `ScrollView`, a `RecyclerView`, the layout around
     * them) is not a stop when it holds actionable nodes: TalkBack moves straight to those.
     */
    private fun isStop(node: UiNode): Boolean {
        val f = node.flags
        if (!f.actionable) return false
        if (f.clickable || f.checkable || f.longClickable) return true
        return node.children.none(::hasActionable)
    }

    private fun hasActionable(node: UiNode): Boolean =
        !node.bounds.isEmpty && (node.flags.actionable || node.children.any(::hasActionable))

    private fun spatiallyOrdered(nodes: List<UiNode>, hierarchy: UiHierarchy): List<UiNode> {
        val tolerance = hierarchy.px(ROW_TOLERANCE_DP)
        return nodes.sortedWith { a, b ->
            val dy = a.bounds.centerY - b.bounds.centerY
            when {
                abs(dy) > tolerance -> dy.compareTo(0)
                else -> a.bounds.left.compareTo(b.bounds.left)
            }
        }
    }

    private fun stop(node: UiNode, mergedText: String, hierarchy: UiHierarchy, viewport: PixelRect, order: Int): AccessibilityStop {
        val role = role(node)
        val flags = node.flags
        val parts = buildList {
            if (mergedText.isNotEmpty()) add(mergedText)
            if (flags.checkable) add(if (flags.checked) "checked" else "not checked")
            if (flags.selected) add("selected")
            if (!flags.enabled) add("disabled")
            if (role.isNotEmpty() && role != "Text") add(role)
            if (flags.clickable && flags.enabled) add("double-tap to activate")
        }
        val issues = buildList {
            val actionable = flags.clickable || flags.focusable || flags.checkable
            if (actionable && mergedText.isEmpty()) {
                add(AccessibilityIssue(AccessibilityIssueKind.NoLabel, "No label: TalkBack says only \"${role.ifEmpty { "unlabeled" }}\""))
            }
            if (flags.naf) add(AccessibilityIssue(AccessibilityIssueKind.NotAccessibilityFriendly, "NAF: actionable without accessible text"))
            if (flags.clickable) smallTarget(node, hierarchy, viewport)?.let(::add)
        }
        return AccessibilityStop(order, node, role, parts.joinToString(", "), issues)
    }

    /** uiautomator clips bounds to the visible part, so a row cut by its list's edge only looks small. */
    private fun smallTarget(node: UiNode, hierarchy: UiHierarchy, viewport: PixelRect): AccessibilityIssue? {
        val r = node.bounds
        val width = hierarchy.dp(r.width)
        val height = hierarchy.dp(r.height)
        val cutVertically = r.top <= viewport.top || r.bottom >= viewport.bottom
        val cutHorizontally = r.left <= viewport.left || r.right >= viewport.right
        val tooNarrow = width < MINIMUM_TARGET_DP && !cutHorizontally
        val tooShort = height < MINIMUM_TARGET_DP && !cutVertically
        if (!tooNarrow && !tooShort) return null
        return AccessibilityIssue(
            AccessibilityIssueKind.SmallTouchTarget,
            "Touch target ${width.roundToInt()} × ${height.roundToInt()} dp, minimum 48 × 48",
        )
    }

    /** What an actionable node announces: its own label, else its descendants' labels in order. */
    private fun mergedText(node: UiNode): String =
        ownText(node).ifEmpty { node.children.map(::mergedText).filter(String::isNotEmpty).joinToString(" ") }

    private fun ownText(node: UiNode): String = when {
        node.flags.password -> "password field"
        node.contentDescription.isNotEmpty() -> node.contentDescription
        else -> node.text
    }

    fun role(node: UiNode): String {
        val cls = node.shortClassName
        return when {
            "Button" in cls && "Radio" !in cls && "Toggle" !in cls -> "Button"
            "EditText" in cls || "AutoComplete" in cls -> "Edit field"
            "CheckBox" in cls -> "Checkbox"
            "Switch" in cls || "ToggleButton" in cls -> "Switch"
            "RadioButton" in cls -> "Radio button"
            "SeekBar" in cls || "Slider" in cls -> "Slider"
            "Spinner" in cls -> "Dropdown"
            "ImageView" in cls -> "Image"
            "TextView" in cls -> if (node.flags.clickable) "Button" else "Text"
            listOf("RecyclerView", "ListView", "ScrollView", "ViewPager").any { it in cls } -> "List"
            "WebView" in cls -> "Web view"
            "Tab" in cls -> "Tab"
            // Compose reports android.view.View for everything; infer from behaviour.
            node.flags.checkable -> "Checkbox"
            node.flags.clickable -> "Button"
            node.flags.scrollable -> "List"
            node.text.isEmpty() && node.contentDescription.isEmpty() -> ""
            else -> "Text"
        }
    }
}
