package dev.acme.adbtoolbox.intellij.logcat

/**
 * Decides whether a Logcat scrollbar adjustment is the *user* scrolling away from the newest line.
 * Only a change of the scroll position counts: lines appended below grow the maximum while the
 * position stays put, and must never switch autoscroll off by themselves.
 */
internal class FollowScrollTracker {
    private var lastValue: Int? = null

    /** Returns true when this adjustment moved the view to somewhere other than the bottom. */
    fun onAdjusted(value: Int, extent: Int, maximum: Int): Boolean {
        val moved = lastValue != null && value != lastValue
        lastValue = value
        return moved && value + extent < maximum
    }

    /** Called after a programmatic jump so it is not mistaken for a user scroll. */
    fun reset(value: Int) {
        lastValue = value
    }
}
