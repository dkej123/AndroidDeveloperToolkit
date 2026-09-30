package dev.acme.adbtoolbox.domain.deeplinks

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProjectDeepLinkEnrichmentTest {
    @Test
    fun `recursively reads navigation graphs and attaches typed destination arguments`() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.acme">
              <application><activity android:name=".MainActivity" android:exported="true">
                <intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/>
                  <data android:scheme="https" android:host="example.com" android:pathPrefix="/products"/>
                </intent-filter>
                <nav-graph android:value="@navigation/root"/>
              </activity></application>
            </manifest>
        """.trimIndent()
        val graphs = mapOf(
            "root" to """
                <navigation xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto">
                  <include app:graph="@navigation/products"/>
                </navigation>
            """.trimIndent(),
            "products" to """
                <navigation xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto">
                  <fragment android:name="com.acme.ProductFragment">
                    <argument android:name="id" app:argType="long"/>
                    <argument android:name="campaign" app:argType="string" android:defaultValue="direct" app:nullable="true"/>
                    <deepLink app:uri="https://example.com/products/{id}?campaign={campaign}"/>
                  </fragment>
                </navigation>
            """.trimIndent(),
        )

        val target = NavigationDeepLinkParser.parse("com.acme", manifest, graphs).targets.single()

        target.componentName shouldBe "com.acme.MainActivity"
        target.sources shouldBe setOf(DeepLinkSource.PROJECT)
        target.parameters shouldBe listOf(
            DeepLinkParameter("id", ParameterLocation.PATH, "long", DeepLinkSource.PROJECT, ParameterRequirement.REQUIRED),
            DeepLinkParameter("campaign", ParameterLocation.QUERY, "string", DeepLinkSource.PROJECT,
                ParameterRequirement.OPTIONAL, defaultValue = "direct", nullable = true),
        )
    }

    @Test
    fun `merges matching project metadata and marks project-only targets runtime unknown`() {
        val installed = DeepLinkCatalog("com.acme", listOf(target("com.acme.Main", DeepLinkSource.APK)))
        val project = DeepLinkCatalog("com.acme", listOf(
            target("com.acme.Main", DeepLinkSource.PROJECT, listOf(parameter("id"))),
            target("com.acme.NewScreen", DeepLinkSource.PROJECT),
        ))

        val merged = ProjectDeepLinkEnrichment.merge(installed, project)

        merged.targets.first { it.componentName == "com.acme.Main" }.let {
            it.sources shouldBe setOf(DeepLinkSource.APK, DeepLinkSource.PROJECT)
            it.parameters shouldBe listOf(parameter("id"))
        }
        merged.targets.first { it.componentName == "com.acme.NewScreen" }.sources shouldBe
            setOf(DeepLinkSource.PROJECT, DeepLinkSource.RUNTIME_UNKNOWN)
    }

    private fun target(name: String, source: DeepLinkSource, parameters: List<DeepLinkParameter> = emptyList()) =
        DeepLinkTarget(name, DeepLinkTargetKind.ACTIVITY, patterns = listOf(UriPattern(schemes = setOf("https"))),
            hasDefaultCategory = true, sources = setOf(source), parameters = parameters)

    private fun parameter(name: String) = DeepLinkParameter(name, ParameterLocation.PATH, "string",
        DeepLinkSource.PROJECT, ParameterRequirement.REQUIRED)
}
