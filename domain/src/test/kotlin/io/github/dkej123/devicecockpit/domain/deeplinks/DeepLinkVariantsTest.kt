package io.github.dkej123.devicecockpit.domain.deeplinks

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun target(vararg patterns: UriPattern, navigationUris: List<String> = emptyList(), parameters: List<DeepLinkParameter> = emptyList()) =
    DeepLinkTarget(
        componentName = "com.acme.app.MainActivity",
        kind = DeepLinkTargetKind.ACTIVITY,
        patterns = patterns.toList(),
        hasDefaultCategory = true,
        parameters = parameters,
        navigationUris = navigationUris,
    )

class DeepLinkVariantsTest {

    @Test
    fun `every scheme, host and path of a filter is its own variant`() {
        val variants = DeepLinkVariants.expand(
            target(
                UriPattern(
                    schemes = setOf("samplestage"),
                    hosts = setOf("verify-email", "rfid-card-ordering"),
                ),
                UriPattern(
                    schemes = setOf("https"),
                    hosts = setOf("acme.com"),
                    paths = listOf(UriMatcher(UriMatcherKind.PATH_LITERAL, "/promo"), UriMatcher(UriMatcherKind.PATH_PREFIX, "/shop")),
                    autoVerify = true,
                ),
            ),
        )

        variants.map { it.template } shouldBe listOf(
            "samplestage://verify-email",
            "samplestage://rfid-card-ordering",
            "https://acme.com/promo",
            "https://acme.com/shop{rest}",
        )
        variants[3].parameters shouldBe listOf(VariantParameter("rest", ParameterLocation.PATH, ParameterRequirement.OPTIONAL))
        variants[2].autoVerify shouldBe true
    }

    @Test
    fun `wildcards in a path pattern become parameters named after the segment before them`() {
        val variants = DeepLinkVariants.expand(
            target(
                UriPattern(
                    schemes = setOf("app"),
                    hosts = setOf("acme"),
                    paths = listOf(UriMatcher(UriMatcherKind.PATH_PATTERN, "/order/.*/items/.*")),
                ),
                UriPattern(
                    schemes = setOf("content"),
                    hosts = setOf("*"),
                    paths = listOf(
                        UriMatcher(UriMatcherKind.PATH_PATTERN, ".*\\.hprof"),
                        UriMatcher(UriMatcherKind.PATH_PATTERN, ".*\\..*\\.hprof"),
                        UriMatcher(UriMatcherKind.PATH_PATTERN, ".*\\..*\\..*\\.hprof"),
                    ),
                ),
            ),
        )

        variants[0].template shouldBe "app://acme/order/{order}/items/{items}"
        variants[0].parameters.map { it.name } shouldBe listOf("order", "items")
        variants.size shouldBe 2 // the extra-dot variants of the same pattern add nothing
        variants[1].template shouldBe "content://{host}{path}.hprof"
        variants[1].parameters.map { it.name to it.requirement } shouldBe listOf(
            "host" to ParameterRequirement.OPTIONAL,
            "path" to ParameterRequirement.REQUIRED,
        )
    }

    @Test
    fun `a navigation deep link gives the exact template, its query parameters and argument types`() {
        val variants = DeepLinkVariants.expand(
            target(
                UriPattern(schemes = setOf("app"), hosts = setOf("acme"), paths = listOf(UriMatcher(UriMatcherKind.PATH_PATTERN, "/user/.*"))),
                UriPattern(schemes = setOf("app"), hosts = setOf("acme"), paths = listOf(UriMatcher(UriMatcherKind.PATH_LITERAL, "/home"))),
                navigationUris = listOf("app://acme/user/{userId}?tab={tab}"),
                parameters = listOf(
                    DeepLinkParameter("userId", ParameterLocation.PATH, "long", DeepLinkSource.PROJECT, ParameterRequirement.REQUIRED),
                    DeepLinkParameter("tab", ParameterLocation.QUERY, "string", DeepLinkSource.PROJECT, ParameterRequirement.OPTIONAL, defaultValue = "info"),
                ),
            ),
        )

        variants.map { it.template } shouldBe listOf("app://acme/user/{userId}?tab={tab}", "app://acme/home")
        variants[0].fromNavigation shouldBe true
        variants[0].parameters shouldBe listOf(
            VariantParameter("userId", ParameterLocation.PATH, ParameterRequirement.REQUIRED, type = "long"),
            VariantParameter("tab", ParameterLocation.QUERY, ParameterRequirement.OPTIONAL, type = "string", defaultValue = "info"),
        )
    }

    @Test
    fun `building fills parameters, drops empty optional query parameters and appends extra ones`() {
        val template = "app://acme/user/{userId}?tab={tab}"

        DeepLinkVariants.build(template, mapOf("userId" to "42", "tab" to ""), listOf("utm source" to "a&b")) shouldBe
            DeepLinkUri("app://acme/user/42?utm+source=a%26b", missing = emptyList())
        DeepLinkVariants.build(template, mapOf("tab" to "orders"), emptyList()) shouldBe
            DeepLinkUri("app://acme/user/{userId}?tab=orders", missing = listOf("userId"))
        DeepLinkVariants.build("https://acme.com/shop{rest}", emptyMap(), listOf("q" to "1"), optional = setOf("rest")) shouldBe
            DeepLinkUri("https://acme.com/shop?q=1", missing = emptyList())
    }
}
