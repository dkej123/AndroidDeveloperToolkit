package io.github.dkej123.devicecockpit.domain.deeplinks

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class AssetLinksParserTest {
    private val json = """[{
      "relation":["delegate_permission/common.handle_all_urls"],
      "target":{"namespace":"android_app","package_name":"com.acme","sha256_cert_fingerprints":["AA:BB"]},
      "relation_extensions":{"delegate_permission/common.handle_all_urls":{"dynamic_app_link_components":[
        {"/":"/private/*","exclude":true},
        {"/":"/products/*","?":{"id":"*"},"#":"details"}
      ]}}
    }]"""

    @Test
    fun `validates relation package certificate and preserves ordered dynamic rules`() {
        val result = AssetLinksParser.parse(json, "com.acme", setOf("AABB")).shouldBeInstanceOf<AssetLinksResult.Valid>()
        result.rules.map { it.exclude } shouldBe listOf(true, false)
        result.rules[1].query shouldBe mapOf("id" to "*")
        result.allows("/private/1", "", "") shouldBe false
        result.allows("/products/1", "id=42", "details") shouldBe true
        result.allows("/other", "", "") shouldBe false
    }

    @Test
    fun `rejects malformed json wrong package relation or certificate`() {
        AssetLinksParser.parse("{", "com.acme", setOf("AABB")).shouldBeInstanceOf<AssetLinksResult.Invalid>()
        AssetLinksParser.parse(json, "other", setOf("AABB")).shouldBeInstanceOf<AssetLinksResult.Invalid>()
        AssetLinksParser.parse(json, "com.acme", setOf("FFFF")).shouldBeInstanceOf<AssetLinksResult.Invalid>()
        AssetLinksParser.parse(json.replace("handle_all_urls", "get_login_creds"), "com.acme", setOf("AABB"))
            .shouldBeInstanceOf<AssetLinksResult.Invalid>()
    }
}
