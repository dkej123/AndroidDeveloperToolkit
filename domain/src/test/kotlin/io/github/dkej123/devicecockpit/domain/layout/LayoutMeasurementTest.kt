package io.github.dkej123.devicecockpit.domain.layout

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LayoutMeasurementTest {
    // 320 dpi: 1 dp = 2 px.
    private val hierarchy = UiHierarchy(UiNode(0, bounds = PixelRect(0, 0, 1080, 2400)), 320)

    @Test
    fun `disjoint elements side by side measure the horizontal gap at their shared middle`() {
        val lines = LayoutMeasurement.between(PixelRect(0, 100, 200, 200), PixelRect(224, 120, 400, 180), hierarchy)
        lines shouldContainExactly listOf(Redline(horizontal = true, fixed = 150, from = 200, to = 224, dp = 12))
    }

    @Test
    fun `disjoint elements diagonally measure both gaps`() {
        val lines = LayoutMeasurement.between(PixelRect(0, 0, 100, 100), PixelRect(140, 160, 200, 200), hierarchy)
        lines.map { it.horizontal to it.dp } shouldContainExactly listOf(true to 20, false to 30)
    }

    @Test
    fun `one inside the other measures the four paddings`() {
        val lines = LayoutMeasurement.between(PixelRect(0, 0, 400, 300), PixelRect(32, 16, 368, 284), hierarchy)
        lines.map { it.dp } shouldContainExactly listOf(16, 16, 8, 8)
        LayoutMeasurement.between(PixelRect(32, 16, 368, 284), PixelRect(0, 0, 400, 300), hierarchy).map { it.dp } shouldContainExactly listOf(16, 16, 8, 8)
    }

    @Test
    fun `overlapping elements measure the left and top edge deltas`() {
        val lines = LayoutMeasurement.between(PixelRect(0, 0, 200, 200), PixelRect(40, 20, 300, 300), hierarchy)
        lines.map { it.horizontal to it.dp } shouldContainExactly listOf(true to 20, false to 10)
    }

    @Test
    fun `text size is estimated from the glyph height`() {
        val text = UiNode(1, text = "Pay", bounds = PixelRect(0, 0, 200, 75)) // 37.5 dp / 1.17 ≈ 32 sp
        LayoutMeasurement.estimatedTextSp(text, hierarchy) shouldBe 32
        LayoutMeasurement.estimatedTextSp(text, hierarchy, fontScale = 2.0) shouldBe 16
        LayoutMeasurement.estimatedTextSp(UiNode(2, bounds = PixelRect(0, 0, 10, 10)), hierarchy) shouldBe null
    }

    @Test
    fun `fit zoom keeps the whole screen visible and never above 200 percent`() {
        LayoutMeasurement.fitZoom(1080, 2400, 540, 600, 320) shouldBe (0.5 plusOrMinus 1e-9)
        LayoutMeasurement.fitZoom(100, 100, 5000, 5000, 160) shouldBe 2.0
    }
}
