package dev.acme.adbtoolbox.domain.layout

import kotlin.math.roundToInt

/** One redline between two elements (design §9): a horizontal or vertical span in pixels with its dp length. */
data class Redline(val horizontal: Boolean, val fixed: Int, val from: Int, val to: Int, val dp: Int)

/**
 * Distances between a selected element and a hovered one, Figma-style (design §9): disjoint → the
 * horizontal and/or vertical gap; one inside the other → the four paddings; overlapping → the
 * left/top edge deltas.
 */
object LayoutMeasurement {

    fun between(selected: PixelRect, hovered: PixelRect, hierarchy: UiHierarchy): List<Redline> {
        fun dp(px: Int) = hierarchy.dp(px).roundToInt()
        val inner: PixelRect
        val outer: PixelRect
        when {
            contains(selected, hovered) -> { outer = selected; inner = hovered }
            contains(hovered, selected) -> { outer = hovered; inner = selected }
            else -> return gapsOrDeltas(selected, hovered, ::dp)
        }
        val midY = inner.centerY
        val midX = inner.centerX
        return listOf(
            Redline(horizontal = true, fixed = midY, from = outer.left, to = inner.left, dp = dp(inner.left - outer.left)),
            Redline(horizontal = true, fixed = midY, from = inner.right, to = outer.right, dp = dp(outer.right - inner.right)),
            Redline(horizontal = false, fixed = midX, from = outer.top, to = inner.top, dp = dp(inner.top - outer.top)),
            Redline(horizontal = false, fixed = midX, from = inner.bottom, to = outer.bottom, dp = dp(outer.bottom - inner.bottom)),
        ).filter { it.to > it.from }
    }

    private fun gapsOrDeltas(a: PixelRect, b: PixelRect, dp: (Int) -> Int): List<Redline> {
        val lines = mutableListOf<Redline>()
        val horizontalGap = when {
            b.left >= a.right -> a.right to b.left
            a.left >= b.right -> b.right to a.left
            else -> null
        }
        val verticalGap = when {
            b.top >= a.bottom -> a.bottom to b.top
            a.top >= b.bottom -> b.bottom to a.top
            else -> null
        }
        if (horizontalGap == null && verticalGap == null) {
            // Overlapping: how far the left and top edges are apart.
            val y = maxOf(a.top, b.top)
            val x = maxOf(a.left, b.left)
            if (a.left != b.left) lines += Redline(true, y, minOf(a.left, b.left), maxOf(a.left, b.left), dp(kotlin.math.abs(a.left - b.left)))
            if (a.top != b.top) lines += Redline(false, x, minOf(a.top, b.top), maxOf(a.top, b.top), dp(kotlin.math.abs(a.top - b.top)))
            return lines
        }
        horizontalGap?.let { (from, to) ->
            val y = overlapMid(a.top, a.bottom, b.top, b.bottom)
            lines += Redline(true, y, from, to, dp(to - from))
        }
        verticalGap?.let { (from, to) ->
            val x = overlapMid(a.left, a.right, b.left, b.right)
            lines += Redline(false, x, from, to, dp(to - from))
        }
        return lines
    }

    /** The middle of the two spans' overlap, or of the first span when they do not overlap. */
    private fun overlapMid(a1: Int, a2: Int, b1: Int, b2: Int): Int {
        val start = maxOf(a1, b1)
        val end = minOf(a2, b2)
        return if (end > start) (start + end) / 2 else (a1 + a2) / 2
    }

    private fun contains(outer: PixelRect, inner: PixelRect) =
        inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom && outer != inner

    /**
     * "≈ 16 sp" for a text node (design §9): text bounds height ÷ line count ÷ 1.17 (line height) ÷
     * font scale, in sp. [lines] defaults to the text's explicit line breaks + 1.
     */
    fun estimatedTextSp(node: UiNode, hierarchy: UiHierarchy, fontScale: Double = 1.0, lines: Int = node.text.count { it == '\n' } + 1): Int? {
        if (node.text.isEmpty() || node.bounds.isEmpty) return null
        return (hierarchy.dp(node.bounds.height) / lines / LINE_HEIGHT / fontScale).roundToInt()
    }

    /** Zoom that fits a [screenWidth]×[screenHeight] px screen into [width]×[height] px of canvas, at most 1:1 dp. */
    fun fitZoom(screenWidth: Int, screenHeight: Int, width: Int, height: Int, densityDpi: Int): Double {
        if (screenWidth <= 0 || screenHeight <= 0 || width <= 0 || height <= 0) return 1.0
        val dpWidth = screenWidth * 160.0 / densityDpi
        val dpHeight = screenHeight * 160.0 / densityDpi
        return minOf(width / dpWidth, height / dpHeight, 2.0)
    }

    private const val LINE_HEIGHT = 1.17
}
