package dev.acme.adbtoolbox.domain.nav

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ViewIdTest {

    @Test
    fun `every destination has a distinct route key`() {
        val routeKeys = ViewId.entries.map { it.routeKey }

        routeKeys.toSet().size shouldBe ViewId.entries.size
    }

    @Test
    fun `fromRouteKey resolves every destination's own route key back to itself`() {
        ViewId.entries.forEach { viewId ->
            ViewId.fromRouteKey(viewId.routeKey) shouldBe viewId
        }
    }

    @Test
    fun `fromRouteKey returns null for an unknown route key`() {
        ViewId.fromRouteKey("not-a-real-view") shouldBe null
    }
}
