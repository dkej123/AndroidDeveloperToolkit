package io.github.dkej123.devicecockpit.domain.layout

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class UiTreeTextTest {

    private val hierarchy = UiHierarchy(
        UiAutomatorXmlParser.parse(javaClass.getResource("/layout/api30-settings.xml")!!.readText()).shouldBeInstanceOf<UiTreeParse.Parsed>().root,
        densityDpi = 440,
    )

    @Test
    fun `one line per useful node with refs and dp frames`() {
        val text = UiTreeText.tree(hierarchy)

        text shouldContain "\"Network & internet\""
        text shouldNotContain "FrameLayout @" // pure wrappers are left out
        UiTreeText.screenSummary(hierarchy) shouldBe "Screen 393x753 dp, 440 dpi"
    }

    @Test
    fun `a line shows class, text, id, frame and flags`() {
        val node = UiNode(
            id = 23, className = "android.widget.Button", text = "Sign in", resourceId = "com.shop:id/sign_in",
            bounds = PixelRect(44, 1925, 1086, 2057), flags = UiNodeFlags(clickable = true),
        )
        UiTreeText.line(node, hierarchy) shouldBe "[23] Button \"Sign in\" #sign_in @16,700 379x48 tap"
    }

    @Test
    fun `interactive only lists actionable elements, flat`() {
        val text = UiTreeText.tree(hierarchy, interactiveOnly = true)

        text.lines().all { it.startsWith("[") } shouldBe true
        text.lines().all { " tap" in it || " scroll" in it || "EditText" in it || "checked" in it } shouldBe true
    }

    @Test
    fun `targets prefer exact text over contains`() {
        val (node, count) = UiTreeText.target("display", hierarchy)!!
        node.text shouldBe "Display"
        count shouldBe 1
        UiTreeText.target("no such text", hierarchy) shouldBe null
    }
}
