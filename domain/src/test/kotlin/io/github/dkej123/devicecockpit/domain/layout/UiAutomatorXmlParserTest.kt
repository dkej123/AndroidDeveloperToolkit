package io.github.dkej123.devicecockpit.domain.layout

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private fun fixture(name: String): String =
    requireNotNull(UiAutomatorXmlParserTest::class.java.getResource("/layout/$name")) { "missing fixture $name" }.readText()

private fun parsed(xml: String): UiNode = UiAutomatorXmlParser.parse(xml).shouldBeInstanceOf<UiTreeParse.Parsed>().root

class UiAutomatorXmlParserTest {

    @Test
    fun `parses every node of a real Settings dump with its bounds, flags and nesting`() {
        val root = parsed(fixture("api30-settings.xml"))

        root.descendantsAndSelf().count() shouldBe 83
        root.className shouldBe "android.widget.FrameLayout"
        root.packageName shouldBe "com.android.settings"
        root.bounds shouldBe PixelRect(0, 0, 1080, 2072)
        val list = root.descendantsAndSelf().single { it.flags.scrollable }
        list.className shouldBe "android.widget.ScrollView"
        list.children.isNotEmpty() shouldBe true
    }

    @Test
    fun `decodes XML entities in attribute values`() {
        val root = parsed(fixture("api30-settings.xml"))

        root.descendantsAndSelf().map { it.text }.filter { "&" in it }.toList() shouldContainExactly listOf("Network & internet", "Apps & notifications")
    }

    @Test
    fun `reads checkable switches of the Display settings`() {
        val switches = parsed(fixture("api30-display-settings.xml")).descendantsAndSelf().filter { it.flags.checkable }.toList()

        switches.map { it.className } shouldContainExactly listOf("android.widget.Switch", "android.widget.Switch")
        switches.map { it.flags.clickable } shouldContainExactly listOf(true, false)
    }

    @Test
    fun `numbers nodes in document order, root first`() {
        val ids = parsed(fixture("api30-anr-dialog.xml")).descendantsAndSelf().map { it.id }.toList()

        ids shouldBe ids.indices.toList()
    }

    @Test
    fun `reads every attribute and flag of a node`() {
        val node = parsed(
            """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">""" +
                """<node index="0" text="Pay &quot;now&quot; &lt;3 &#233;&#x41;" resource-id="com.shop:id/pay" class="android.widget.Button" """ +
                """package="com.shop" content-desc="Pay" checkable="true" checked="true" clickable="true" enabled="false" """ +
                """focusable="true" focused="false" scrollable="true" long-clickable="true" password="true" selected="true" """ +
                """NAF="true" bounds="[10,20][110,220]" /></hierarchy>""",
        )

        node.text shouldBe "Pay \"now\" <3 éA"
        node.resourceId shouldBe "com.shop:id/pay"
        node.contentDescription shouldBe "Pay"
        node.bounds shouldBe PixelRect(10, 20, 110, 220)
        node.flags shouldBe UiNodeFlags(
            clickable = true, longClickable = true, focusable = true, enabled = false, checkable = true,
            checked = true, selected = true, scrollable = true, password = true, naf = true,
        )
    }

    @Test
    fun `a node without bounds gets an empty rectangle instead of failing the dump`() {
        parsed("""<hierarchy><node class="a.B" bounds="[1,2]"/></hierarchy>""").bounds shouldBe PixelRect.EMPTY
    }

    @Test
    fun `missing hierarchy, no nodes and unbalanced tags are malformed`() {
        UiAutomatorXmlParser.parse("").shouldBeInstanceOf<UiTreeParse.Malformed>()
        UiAutomatorXmlParser.parse("ERROR: could not get idle state.").shouldBeInstanceOf<UiTreeParse.Malformed>()
        UiAutomatorXmlParser.parse("<hierarchy rotation=\"0\"></hierarchy>").shouldBeInstanceOf<UiTreeParse.Malformed>()
        UiAutomatorXmlParser.parse("<hierarchy><node class=\"a\" bounds=\"[0,0][1,1]\">").shouldBeInstanceOf<UiTreeParse.Malformed>()
        UiAutomatorXmlParser.parse("<hierarchy></node></hierarchy>").shouldBeInstanceOf<UiTreeParse.Malformed>()
    }

    @Test
    fun `several top-level windows are wrapped in one synthetic root spanning them`() {
        val root = parsed(
            """<hierarchy><node class="w1" bounds="[0,0][100,50]"/><node class="w2" bounds="[0,50][100,200]"/></hierarchy>""",
        )

        root.className shouldBe ""
        root.bounds shouldBe PixelRect(0, 0, 100, 200)
        root.children.map { it.className } shouldContainExactly listOf("w1", "w2")
    }
}
