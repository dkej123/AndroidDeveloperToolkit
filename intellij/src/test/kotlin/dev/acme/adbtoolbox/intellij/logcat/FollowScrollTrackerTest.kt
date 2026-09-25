package dev.acme.adbtoolbox.intellij.logcat

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
        tracker.onAdjusted(value = 400, extent = 100, maximum = 500)

        assertTrue(tracker.onAdjusted(value = 120, extent = 100, maximum = 500))
    }

    fun `test moving the view while it still shows the newest line is not scrolling away`() {
        val tracker = FollowScrollTracker()
        tracker.onAdjusted(value = 300, extent = 100, maximum = 500)

        assertFalse(tracker.onAdjusted(value = 400, extent = 100, maximum = 500))
    }
}
