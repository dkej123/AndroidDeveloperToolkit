package io.github.dkej123.devicecockpit.domain.layout

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun node(id: Int, rect: PixelRect, className: String = "android.view.View", vararg children: UiNode) =
    UiNode(id = id, className = className, bounds = rect, children = children.toList())

class UiHierarchyTest {

    private val button = node(2, PixelRect(100, 100, 300, 200), "android.widget.Button")
    private val card = node(1, PixelRect(50, 50, 500, 500), "android.widget.LinearLayout", button)
    private val hidden = node(3, PixelRect(120, 120, 120, 180))
    private val root = node(0, PixelRect(0, 0, 1080, 2340), "android.widget.FrameLayout", card, hidden)

    @Test
    fun `converts pixels to dp with the effective density`() {
        val hierarchy = UiHierarchy(root, densityDpi = 440)

        hierarchy.dp(440) shouldBe (160.0 plusOrMinus 1e-9)
        hierarchy.px(48.0) shouldBe (132.0 plusOrMinus 1e-9)
        UiHierarchy(root, densityDpi = 160).dp(48) shouldBe (48.0 plusOrMinus 1e-9)
    }

    @Test
    fun `hit test returns the deepest smallest node and skips zero-size ones`() {
        val hierarchy = UiHierarchy(root, densityDpi = 440)

        hierarchy.hitTest(150, 150) shouldBe button
        hierarchy.hitTest(60, 60) shouldBe card
        hierarchy.hitTest(900, 2000) shouldBe root
        hierarchy.hitTest(2000, 2000) shouldBe null
    }

    @Test
    fun `finds nodes by id and exposes short names and a label`() {
        val hierarchy = UiHierarchy(root, densityDpi = 440)

        hierarchy.node(2) shouldBe button
        hierarchy.node(99) shouldBe null
        button.shortClassName shouldBe "Button"
        UiNode(id = 0, resourceId = "com.shop:id/title").shortResourceId shouldBe "title"
        UiNode(id = 0, text = "Pay", resourceId = "com.shop:id/pay").label shouldBe "\"Pay\""
        UiNode(id = 0, resourceId = "com.shop:id/pay", contentDescription = "Pay").label shouldBe "pay"
        UiNode(id = 0, contentDescription = "Back").label shouldBe "Back"
        button.label shouldBe "Button"
    }

    @Test
    fun `pixel rectangles know their size, centre and containment`() {
        val rect = PixelRect(10, 20, 110, 220)

        rect.width shouldBe 100
        rect.height shouldBe 200
        rect.centerX shouldBe 60
        rect.centerY shouldBe 120
        rect.contains(10, 20) shouldBe true
        rect.contains(110, 220) shouldBe false
        rect.isEmpty shouldBe false
        PixelRect.EMPTY.isEmpty shouldBe true
    }
}
