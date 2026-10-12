package io.github.dkej123.devicecockpit.intellij.logcat

/**
 * Decides whether a Logcat scrollbar adjustment is the *user* scrolling away from the newest line.
 * It counts only when the user just scrolled (wheel, keys, scrollbar clicks — [userInput]) or is
 * dragging the thumb (`adjusting`), and only a change of position with the content and the
 * viewport unchanged: lines appended below grow the maximum, a reload replaces the rows, the
 * buffer evicting old lines makes the viewport shift the view before the maximum catches up, a
 * resized tool window changes the extent — none of them may switch autoscroll off by themselves.
 */
internal class FollowScrollTracker {
    private var lastValue: Int? = null
    private var lastExtent = 0
    private var lastMaximum = 0
    private var lastUserInput = Long.MIN_VALUE

    /** The user scrolled (wheel, navigation key, scrollbar press) at [now]. */
    fun userInput(now: Long = System.currentTimeMillis()) {
        lastUserInput = now
    }

    /** Returns true when this adjustment moved the view to somewhere other than the bottom. */
    fun onAdjusted(value: Int, extent: Int, maximum: Int, adjusting: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        val moved = lastValue != null && value != lastValue && extent == lastExtent && maximum == lastMaximum
        lastValue = value
        lastExtent = extent
        lastMaximum = maximum
        val byUser = adjusting || (lastUserInput != Long.MIN_VALUE && now - lastUserInput <= USER_INPUT_WINDOW_MS)
        return moved && byUser && value + extent < maximum
    }

    /** Called after a programmatic jump so it is not mistaken for a user scroll. */
    fun reset(value: Int) {
        lastValue = value
    }

    private companion object {
        /** How long after a wheel tick or key press the view moving still counts as the user's doing. */
        const val USER_INPUT_WINDOW_MS = 500L
    }
}
