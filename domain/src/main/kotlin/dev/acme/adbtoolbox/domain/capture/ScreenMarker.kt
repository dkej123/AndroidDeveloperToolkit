package dev.acme.adbtoolbox.domain.capture

/** A rectangle on a screenshot, in its pixels — dp for MCP screenshots, which are scaled to 1 px = 1 dp. */
data class MarkBox(val left: Int, val top: Int, val width: Int, val height: Int) {
    val centerX: Double get() = left + width / 2.0
    val centerY: Double get() = top + height / 2.0
    val area: Long get() = width.toLong() * height

    /** Area shared with [other]. */
    fun overlap(other: MarkBox): Long {
        val w = minOf(left + width, other.left + other.width) - maxOf(left, other.left)
        val h = minOf(top + height, other.top + other.height) - maxOf(top, other.top)
        return if (w > 0 && h > 0) w.toLong() * h else 0
    }
}

/** A labelled box drawn on a screenshot: a UI-tree element (labelled with its ref) or a shape found in the pixels. */
data class ScreenMark(val label: String, val box: MarkBox, val fromTree: Boolean)

/**
 * Finds element-like shapes in a screenshot and draws numbered boxes on it (an `:adapters-jvm` port),
 * so an agent can point at things the UI tree does not describe — games, canvas, Flutter (task 066).
 */
interface ScreenMarker {
    /** Shapes that look like UI elements, top to bottom; empty when [png] cannot be decoded. */
    fun detect(png: ByteArray): List<MarkBox>

    /** [png] with every mark outlined and labelled, or null when it cannot be decoded. */
    fun draw(png: ByteArray, marks: List<ScreenMark>): ByteArray?
}
