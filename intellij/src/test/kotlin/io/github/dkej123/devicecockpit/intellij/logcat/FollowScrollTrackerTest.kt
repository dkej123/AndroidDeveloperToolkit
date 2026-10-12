package io.github.dkej123.devicecockpit.intellij.logcat

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FollowScrollTrackerTest : BasePlatformTestCase() {

    fun `test new lines growing the content below the view are not the user scrolling away`() {
        // Regression (docs/e2e-testing.md): every append grew the scrollbar's maximum, the panel
        // saw "not at bottom" and switched autoscroll off by itself.
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 400, extent = 100, maximum = 500)

        assertFalse(tracker.onAdjusted(value = 400, extent = 100, maximum = 900))
    }

    fun `test the user moving the view up away from the bottom is scrolling away`() {
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 400, extent = 100, maximum = 500, now = 1_000)
        tracker.userInput(now = 1_000)

        assertTrue(tracker.onAdjusted(value = 120, extent = 100, maximum = 500, now = 1_050))
    }

    fun `test dragging the scrollbar thumb away from the bottom is scrolling away`() {
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 400, extent = 100, maximum = 500)

        assertTrue(tracker.onAdjusted(value = 120, extent = 100, maximum = 500, adjusting = true))
    }

    fun `test the viewport moving the view without any user input is not scrolling away`() {
        // Regression (E2E, 2026-10-13): the buffer evicting old lines made ViewportLayout move the
        // view before the scrollbar's maximum caught up; autoscroll switched itself off.
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 209_855, extent = 763, maximum = 210_618, now = 1_000)
        tracker.userInput(now = 0)

        assertFalse(tracker.onAdjusted(value = 209_775, extent = 763, maximum = 210_618, now = 5_000))
    }

    fun `test moving the view while it still shows the newest line is not scrolling away`() {
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 300, extent = 100, maximum = 500)

        assertFalse(tracker.onAdjusted(value = 400, extent = 100, maximum = 500))
    }

    fun `test the list being replaced and clamping the view is not the user scrolling away`() {
        // Regression (E2E, 2026-10-13): showing Logcat reloads the buffer; the rows were replaced, the
        // view clamped to the top of the new content and autoscroll switched itself off.
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 3_000, extent = 600, maximum = 3_600)

        assertFalse(tracker.onAdjusted(value = 0, extent = 600, maximum = 600))
        assertFalse(tracker.onAdjusted(value = 0, extent = 600, maximum = 9_000))
    }

    fun `test the viewport resizing is not the user scrolling away`() {
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 3_000, extent = 600, maximum = 3_600)

        assertFalse(tracker.onAdjusted(value = 2_800, extent = 300, maximum = 3_600))
    }
}
