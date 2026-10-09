package io.github.dkej123.devicecockpit.domain.layout

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private const val DPI = 440 // 1 dp = 2.75 px
private fun dp(value: Int) = (value * DPI) / 160

private fun n(
    id: Int,
    rect: PixelRect,
    className: String = "android.view.View",
    text: String = "",
    desc: String = "",
    flags: UiNodeFlags = UiNodeFlags(),
    vararg children: UiNode,
) = UiNode(id = id, className = className, text = text, contentDescription = desc, bounds = rect, flags = flags, children = children.toList())

private fun audit(vararg children: UiNode, rootFlags: UiNodeFlags = UiNodeFlags()) =
    AccessibilityAudit.items(UiHierarchy(n(0, PixelRect(0, 0, 1080, 2340), "android.widget.FrameLayout", flags = rootFlags, children = children), DPI))

private val CLICKABLE = UiNodeFlags(clickable = true)
private fun big(top: Int) = PixelRect(0, top, dp(200), top + dp(56))

class AccessibilityAuditTest {

    @Test
    fun `an actionable node is one stop that merges its descendants' text`() {
        val row = n(1, big(0), "android.widget.LinearLayout", flags = CLICKABLE, children = arrayOf(
            n(2, big(0), "android.widget.TextView", text = "Wi-Fi"),
            n(3, big(0), "android.widget.TextView", text = "Connected"),
        ))

        val items = audit(row)

        items.map { it.node.id } shouldContainExactly listOf(1)
        items.single().spoken shouldBe "Wi-Fi Connected, Button, double-tap to activate"
        items.single().order shouldBe 1
    }

    @Test
    fun `plain text is a stop, pure containers are skipped`() {
        val items = audit(n(1, big(0), "android.widget.LinearLayout", children = arrayOf(n(2, big(0), "android.widget.TextView", text = "Title"))))

        items.map { it.node.id } shouldContainExactly listOf(2)
        items.single().spoken shouldBe "Title"
        items.single().role shouldBe "Text"
    }

    @Test
    fun `siblings are read in rows top to bottom, then left to right within a row`() {
        val right = n(1, PixelRect(dp(100), dp(4), dp(200), dp(40)), text = "Right")
        val left = n(2, PixelRect(0, 0, dp(100), dp(48)), text = "Left")
        val below = n(3, PixelRect(0, dp(60), dp(100), dp(100)), text = "Below")

        audit(below, right, left).map { it.node.text } shouldContainExactly listOf("Left", "Right", "Below")
    }

    @Test
    fun `an unlabeled button is reported with what TalkBack says`() {
        val item = audit(n(1, big(0), "android.widget.ImageButton", flags = CLICKABLE)).single()

        item.issues.map { it.kind } shouldContainExactly listOf(AccessibilityIssueKind.NoLabel)
        item.issues.single().message shouldBe "No label: TalkBack says only \"Button\""
    }

    @Test
    fun `NAF, decorative-looking images and small touch targets are reported`() {
        val naf = n(1, big(0), flags = UiNodeFlags(clickable = true, naf = true), text = "x")
        val image = n(2, big(dp(100)), "android.widget.ImageView", desc = "")
        val small = n(3, PixelRect(0, dp(300), dp(40), dp(330)), "android.widget.Button", text = "Ok", flags = CLICKABLE)

        val issues = audit(naf, image, small).associate { it.node.id to it.issues.map { issue -> issue.kind } }

        issues[1] shouldBe listOf(AccessibilityIssueKind.NotAccessibilityFriendly)
        issues[2] shouldBe null // not a stop: see unannouncedImages
        issues[3] shouldBe listOf(AccessibilityIssueKind.SmallTouchTarget)
        audit(small).single().issues.single().message shouldBe "Touch target 40 × 30 dp, minimum 48 × 48"
    }

    @Test
    fun `images TalkBack skips are listed separately, unless they sit inside a control`() {
        val decorative = n(1, big(0), "android.widget.ImageView")
        val described = n(2, big(dp(100)), "android.widget.ImageView", desc = "Logo")
        val inButton = n(3, big(dp(200)), "android.widget.LinearLayout", flags = CLICKABLE, text = "Share", children = arrayOf(
            n(4, big(dp(200)), "android.widget.ImageView"),
        ))
        val tiny = n(5, PixelRect(0, dp(300), 0, dp(310)), "android.widget.ImageView")

        val report = AccessibilityAudit.audit(UiHierarchy(n(0, PixelRect(0, 0, 1080, 2340), children = arrayOf(decorative, described, inButton, tiny)), DPI))

        report.unannouncedImages.map { it.id } shouldContainExactly listOf(1)
        report.stops.map { it.node.id } shouldContainExactly listOf(2, 3)
        report.stops.flatMap { it.issues }.shouldBeEmpty()
    }

    @Test
    fun `targets cut by the edge of a scrolling list are not reported as small`() {
        val list = n(1, PixelRect(0, dp(100), dp(400), dp(500)), "android.widget.ScrollView", flags = UiNodeFlags(scrollable = true), children = arrayOf(
            n(2, PixelRect(0, dp(100), dp(400), dp(120)), "android.widget.Button", text = "Half visible", flags = CLICKABLE),
            n(3, PixelRect(0, dp(200), dp(400), dp(220)), "android.widget.Button", text = "Too short", flags = CLICKABLE),
        ))

        audit(list).associate { it.node.id to it.issues.map { issue -> issue.kind } } shouldBe mapOf(
            2 to emptyList(),
            3 to listOf(AccessibilityIssueKind.SmallTouchTarget),
        )
    }

    @Test
    fun `announces state and role, and hides password text`() {
        val switch = n(1, big(0), "android.widget.Switch", text = "Dark theme", flags = UiNodeFlags(clickable = true, checkable = true, checked = true))
        val disabled = n(2, big(dp(100)), "android.widget.Button", text = "Send", flags = UiNodeFlags(clickable = true, enabled = false))
        val password = n(3, big(dp(200)), "android.widget.EditText", text = "hunter2", flags = UiNodeFlags(focusable = true, password = true))

        audit(switch, disabled, password).map { it.spoken } shouldContainExactly listOf(
            "Dark theme, checked, Switch, double-tap to activate",
            "Send, disabled, Button",
            "password field, Edit field",
        )
    }

    @Test
    fun `Compose nodes get their role from behaviour`() {
        AccessibilityAudit.role(UiNode(id = 0, className = "android.view.View", flags = UiNodeFlags(checkable = true))) shouldBe "Checkbox"
        AccessibilityAudit.role(UiNode(id = 0, className = "android.view.View", flags = CLICKABLE)) shouldBe "Button"
        AccessibilityAudit.role(UiNode(id = 0, className = "android.view.View", flags = UiNodeFlags(scrollable = true))) shouldBe "List"
        AccessibilityAudit.role(UiNode(id = 0, className = "android.view.View", text = "Hi")) shouldBe "Text"
        AccessibilityAudit.role(UiNode(id = 0, className = "android.view.View")) shouldBe ""
        AccessibilityAudit.role(UiNode(id = 0, className = "android.widget.TextView", flags = CLICKABLE)) shouldBe "Button"
        AccessibilityAudit.role(UiNode(id = 0, className = "android.widget.SeekBar")) shouldBe "Slider"
    }

    @Test
    fun `the Settings screen reads top to bottom with its search bar first`() {
        val root = UiAutomatorXmlParser.parse(javaClass.getResource("/layout/api30-settings.xml")!!.readText())
            .shouldBeInstanceOf<UiTreeParse.Parsed>().root

        val items = AccessibilityAudit.items(UiHierarchy(root, 440))

        items.first().spoken shouldContain "Search settings"
        items.map { it.order } shouldBe (1..items.size).toList()
        items.zipWithNext().all { (a, b) -> a.node.bounds.top <= b.node.bounds.top + 50 } shouldBe true
    }

    @Test
    fun `a focusable scrolling container is not a stop, its rows are`() {
        val items = audit(
            n(1, PixelRect(0, 0, 1080, 2000), "android.widget.ScrollView", flags = UiNodeFlags(focusable = true, scrollable = true), children = arrayOf(
                n(2, big(0), "android.widget.LinearLayout", flags = CLICKABLE, children = arrayOf(n(3, big(0), "android.widget.TextView", text = "Network"))),
                n(4, big(dp(100)), "android.widget.LinearLayout", flags = CLICKABLE, children = arrayOf(n(5, big(dp(100)), "android.widget.TextView", text = "Display"))),
            )),
        )

        items.map { it.spoken } shouldContainExactly listOf("Network, Button, double-tap to activate", "Display, Button, double-tap to activate")
    }

    @Test
    fun `the Settings screen lists each row of its focusable ScrollView`() {
        val root = UiAutomatorXmlParser.parse(javaClass.getResource("/layout/api30-settings.xml")!!.readText())
            .shouldBeInstanceOf<UiTreeParse.Parsed>().root

        val items = AccessibilityAudit.items(UiHierarchy(root, 440))

        val rows = listOf("Network & internet", "Connected devices", "Apps & notifications", "Battery", "Display", "Sound", "Storage")
        rows.forEach { row -> items.count { it.spoken.startsWith(row) } shouldBe 1 }
        items.none { "ScrollView" in it.node.className || "RecyclerView" in it.node.className } shouldBe true
    }

    @Test
    fun `the report lists every stop with its problems`() {
        val report = AccessibilityAudit.audit(UiHierarchy(n(0, PixelRect(0, 0, 1080, 2340), children = arrayOf(
            n(1, big(0), "android.widget.ImageButton", flags = CLICKABLE),
            n(2, big(dp(100)), "android.widget.TextView", text = "Hello"),
            n(3, big(dp(200)), "android.widget.ImageView"),
        )), DPI)).markdown()

        report shouldBe """
            |# Accessibility audit
            |
            |2 stops, 1 with problems; 1 image without a description.
            |
            || # | Announced | Problems |
            ||---|---|---|
            || 1 | Button, double-tap to activate | No label: TalkBack says only "Button" |
            || 2 | Hello | — |
            |
            |## Images TalkBack skips
            |
            |Fine when decorative; give them a content description when they carry meaning.
            |
            |- ImageView at [0,550][550,704] px
            |""".trimMargin()
    }
}
