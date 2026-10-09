package io.github.dkej123.devicecockpit.domain.appdata

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private val ANDROID_WRITTEN = """
    <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
    <map>
        <string name="token">abc &amp; &lt;def&gt; &quot;q&quot;</string>
        <int name="launches" value="12" />
        <long name="lastSeen" value="1726000000000" />
        <float name="ratio" value="0.75" />
        <boolean name="onboarded" value="true" />
        <set name="tags">
            <string>a</string>
            <string>b &amp; c</string>
        </set>
        <string name="empty"></string>
        <string name="selfClosed" />
        <null name="gone" />
    </map>
""".trimIndent()

class SharedPrefsXmlTest {

    @Test
    fun `parses every value type Android writes`() {
        val prefs = (SharedPrefsXml.parse(ANDROID_WRITTEN) as SharedPrefsParse.Parsed).entries

        prefs shouldBe listOf(
            PrefEntry("token", PrefValue.Text("abc & <def> \"q\"")),
            PrefEntry("launches", PrefValue.IntValue(12)),
            PrefEntry("lastSeen", PrefValue.LongValue(1_726_000_000_000)),
            PrefEntry("ratio", PrefValue.FloatValue(0.75f)),
            PrefEntry("onboarded", PrefValue.BooleanValue(true)),
            PrefEntry("tags", PrefValue.StringSet(listOf("a", "b & c"))),
            PrefEntry("empty", PrefValue.Text("")),
            PrefEntry("selfClosed", PrefValue.Text("")),
            PrefEntry("gone", PrefValue.Null),
        )
    }

    @Test
    fun `serializing and parsing again round-trips, escaping markup`() {
        val entries = (SharedPrefsXml.parse(ANDROID_WRITTEN) as SharedPrefsParse.Parsed).entries

        val xml = SharedPrefsXml.serialize(entries)

        (SharedPrefsXml.parse(xml) as SharedPrefsParse.Parsed).entries shouldBe entries
        xml.contains("abc &amp; &lt;def&gt;") shouldBe true
        xml.startsWith("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>") shouldBe true
    }

    @Test
    fun `an empty map and numeric character references parse`() {
        (SharedPrefsXml.parse("<?xml version='1.0' ?><map />") as SharedPrefsParse.Parsed).entries shouldBe emptyList()
        (SharedPrefsXml.parse("<map><string name=\"e\">&#233;&#x41;</string></map>") as SharedPrefsParse.Parsed).entries shouldBe
            listOf(PrefEntry("e", PrefValue.Text("éA")))
    }

    @Test
    fun `malformed files are reported, never thrown`() {
        SharedPrefsXml.parse("not xml").shouldBeInstanceOf<SharedPrefsParse.Malformed>()
        SharedPrefsXml.parse("<map><int name=\"x\" value=\"abc\" /></map>").shouldBeInstanceOf<SharedPrefsParse.Malformed>()
        SharedPrefsXml.parse("<map><string name=\"x\">unterminated</map>").shouldBeInstanceOf<SharedPrefsParse.Malformed>()
    }

    @Test
    fun `typed values are parsed from user input for editing`() {
        PrefValue.fromInput(PrefType.Int, "42") shouldBe PrefValue.IntValue(42)
        PrefValue.fromInput(PrefType.Int, "4.2") shouldBe null
        PrefValue.fromInput(PrefType.Boolean, "TRUE") shouldBe PrefValue.BooleanValue(true)
        PrefValue.fromInput(PrefType.Boolean, "yes") shouldBe null
        PrefValue.fromInput(PrefType.StringSet, "a\nb") shouldBe PrefValue.StringSet(listOf("a", "b"))
        PrefValue.fromInput(PrefType.Text, "x") shouldBe PrefValue.Text("x")
        PrefValue.StringSet(listOf("a", "b")).display shouldBe "a\nb"
    }
}
