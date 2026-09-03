package dev.acme.adbtoolbox.domain.nav

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NavigationBadgesTest {

    @Test
    fun `a fresh aggregate has no badges for any view`() {
        val badges = MutableNavigationBadges()

        badges.state.value shouldBe emptyMap()
    }

    @Test
    fun `setting a badge for one view does not affect another view's entry`() {
        val badges = MutableNavigationBadges()

        badges.set(ViewId.Display, NavigationBadge.Attention)
        badges.set(ViewId.Logcat, NavigationBadge.Count(3))

        badges.state.value shouldBe mapOf(
            ViewId.Display to NavigationBadge.Attention,
            ViewId.Logcat to NavigationBadge.Count(3),
        )
    }

    @Test
    fun `setting a view's badge to None clears its entry entirely`() {
        val badges = MutableNavigationBadges()
        badges.set(ViewId.Network, NavigationBadge.Attention)

        badges.set(ViewId.Network, NavigationBadge.None)

        badges.state.value shouldBe emptyMap()
    }

    @Test
    fun `setting the same view's badge again replaces the previous value`() {
        val badges = MutableNavigationBadges()
        badges.set(ViewId.Apps, NavigationBadge.Count(1))

        badges.set(ViewId.Apps, NavigationBadge.Count(2))

        badges.state.value shouldBe mapOf(ViewId.Apps to NavigationBadge.Count(2))
    }
}
