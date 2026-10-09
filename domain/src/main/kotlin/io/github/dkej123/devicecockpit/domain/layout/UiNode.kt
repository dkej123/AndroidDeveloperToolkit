package io.github.dkej123.devicecockpit.domain.layout

/** An axis-aligned rectangle in device pixels; [right] and [bottom] are exclusive, as `uiautomator` reports them. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    companion object {
        val EMPTY = PixelRect(0, 0, 0, 0)
    }
}

/** The accessibility flags `uiautomator dump` reports for a node; [naf] is its own "not accessibility friendly" verdict. */
data class UiNodeFlags(
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val enabled: Boolean = true,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
    val naf: Boolean = false,
) {
    val actionable: Boolean get() = clickable || focusable || checkable || longClickable
}

/**
 * One node of the screen's accessibility hierarchy (ADR 0014): a View or a Compose semantics node.
 * [id] is its position in document order within one capture, root = 0, and is unique there.
 *
 * Ported from Oh My Android, MIT — `Sources/Core/Android/LayoutSnapshot.swift` (`UINode`).
 */
data class UiNode(
    val id: Int,
    val className: String = "",
    val resourceId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val packageName: String = "",
    val bounds: PixelRect = PixelRect.EMPTY,
    val flags: UiNodeFlags = UiNodeFlags(),
    val children: List<UiNode> = emptyList(),
) {
    /** `TextView` instead of `android.widget.TextView`. */
    val shortClassName: String get() = className.substringAfterLast('.')

    /** `title` instead of `com.example.app:id/title`. */
    val shortResourceId: String get() = resourceId.substringAfterLast('/')

    /** What a person would call this node: its text, else its id, else its description, else its class. */
    val label: String
        get() = when {
            text.isNotEmpty() -> "\"$text\""
            resourceId.isNotEmpty() -> shortResourceId
            contentDescription.isNotEmpty() -> contentDescription
            else -> shortClassName
        }

    /** This node and all its descendants, depth-first in document order. */
    fun descendantsAndSelf(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.descendantsAndSelf()) }
    }

    /** The deepest, smallest node containing the pixel, skipping zero-size nodes; null when outside. */
    fun hitTest(x: Int, y: Int): UiNode? {
        if (bounds.isEmpty || !bounds.contains(x, y)) return null
        return children.mapNotNull { it.hitTest(x, y) }.minByOrNull { it.bounds.width.toLong() * it.bounds.height } ?: this
    }
}

/** A captured hierarchy with the effective density needed to speak in dp (sizes follow display-size overrides). */
data class UiHierarchy(val root: UiNode, val densityDpi: Int) {
    private val byId: Map<Int, UiNode> by lazy { root.descendantsAndSelf().associateBy { it.id } }

    fun dp(pixels: Int): Double = dp(pixels.toDouble())

    fun dp(pixels: Double): Double = pixels * MDPI / densityDpi

    fun px(dp: Double): Double = dp * densityDpi / MDPI

    fun hitTest(x: Int, y: Int): UiNode? = root.hitTest(x, y)

    fun node(id: Int): UiNode? = byId[id]

    private companion object {
        const val MDPI = 160.0
    }
}
