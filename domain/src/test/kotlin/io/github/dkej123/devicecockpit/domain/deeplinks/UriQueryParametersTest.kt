package io.github.dkej123.devicecockpit.domain.deeplinks

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UriQueryParametersTest {
    @Test
    fun `an added blank row leaves the URI intact and emoji round trips as UTF8`() {
        UriQueryParameters.replace("test://item#details", listOf("" to "")) shouldBe "test://item#details"
        val uri = UriQueryParameters.replace("test://item", listOf("emoji" to "😄"))
        uri shouldBe "test://item?emoji=%F0%9F%98%84"
        UriQueryParameters.parse(uri) shouldBe listOf("emoji" to "😄")
    }

    @Test
    fun `editing query pairs preserves fragments and repeated keys and encodes UTF8 and reserved characters`() {
        val uri = "myapp://product/42?ref=old#details?not-a-query=1"
        val result = UriQueryParameters.replace(uri, listOf("ref" to "IDE & QA", "tag" to "Łódź", "tag" to "a+b='x'"))
        result shouldBe "myapp://product/42?ref=IDE%20%26%20QA&tag=%C5%81%C3%B3d%C5%BA&tag=a%2Bb%3D%27x%27#details?not-a-query=1"
        UriQueryParameters.parse(result) shouldBe listOf("ref" to "IDE & QA", "tag" to "Łódź", "tag" to "a+b='x'")
    }
    @Test
    fun `URI edits decode query fields and removing every pair removes the question mark`() {
        UriQueryParameters.parse("https://example.com?a=one+two&empty=&flag&x=a%3Db#anchor") shouldBe
            listOf("a" to "one two", "empty" to "", "flag" to "", "x" to "a=b")
        UriQueryParameters.replace("https://example.com?x=1#anchor", emptyList()) shouldBe "https://example.com#anchor"
        UriQueryParameters.parse("https://example.com#anchor?x=1") shouldBe emptyList()
        UriQueryParameters.parse("test://item?value=%zz") shouldBe listOf("value" to "%zz")
    }
}
