package io.github.dkej123.devicecockpit.domain.deeplinks

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ManifestDeepLinkParserTest {
    @Test
    fun `combines all data elements in one filter and ignores non browsable activities`() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.acme.shop">
              <application>
                <activity android:name=".ProductActivity" android:exported="true">
                  <intent-filter android:autoVerify="true">
                    <action android:name="android.intent.action.VIEW" />
                    <category android:name="android.intent.category.BROWSABLE" />
                    <data android:scheme="https" />
                    <data android:host="shop.example.com" android:pathPrefix="/products/" />
                    <data android:queryAdvancedPattern="id=.*" android:fragment="details" />
                  </intent-filter>
                </activity>
                <activity android:name=".Hidden" android:exported="false">
                  <intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="x"/></intent-filter>
                </activity>
              </application>
            </manifest>
        """.trimIndent()

        val catalog = ManifestDeepLinkParser.parse(manifest, "base.apk")

        catalog.packageName shouldBe "com.acme.shop"
        catalog.targets.size shouldBe 1
        catalog.targets.single().let { target ->
            target.componentName shouldBe "com.acme.shop.ProductActivity"
            target.hasDefaultCategory shouldBe false
            target.patterns shouldBe listOf(
                UriPattern(
                    schemes = setOf("https"), hosts = setOf("shop.example.com"),
                    paths = listOf(UriMatcher(UriMatcherKind.PATH_PREFIX, "/products/")),
                    queries = listOf(UriMatcher(UriMatcherKind.QUERY_ADVANCED_PATTERN, "id=.*")),
                    fragments = listOf(UriMatcher(UriMatcherKind.FRAGMENT_LITERAL, "details")),
                    autoVerify = true,
                ),
            )
        }
    }

    @Test
    fun `supports alias wildcard host mime ssp and matcher families`() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="p">
              <application><activity-alias android:name="Alias" android:targetActivity=".Main" android:exported="true">
                <intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/><category android:name="android.intent.category.DEFAULT"/>
                  <data android:scheme="custom" android:host="*.example.com" android:port="8443" android:mimeType="text/plain"
                    android:sspPattern="item.*" android:path="/a" android:pathPattern="/b.*" android:pathAdvancedPattern="/c.*"
                    android:pathSuffix=".html" android:query="x=1" android:queryPattern="y=.*" android:fragmentPattern="f.*" android:fragmentAdvancedPattern="g.*"/>
                </intent-filter>
              </activity-alias></application>
            </manifest>
        """.trimIndent()

        val target = ManifestDeepLinkParser.parse(manifest).targets.single()
        target.kind shouldBe DeepLinkTargetKind.ACTIVITY_ALIAS
        target.targetActivity shouldBe "p.Main"
        target.hasDefaultCategory shouldBe true
        target.patterns.single().let { pattern ->
            pattern.hosts shouldBe setOf("*.example.com")
            pattern.ports shouldBe setOf("8443")
            pattern.mimeTypes shouldBe setOf("text/plain")
            pattern.ssp shouldBe listOf(UriMatcher(UriMatcherKind.SSP_PATTERN, "item.*"))
            pattern.paths.map { it.kind } shouldBe listOf(UriMatcherKind.PATH_LITERAL, UriMatcherKind.PATH_SUFFIX, UriMatcherKind.PATH_PATTERN, UriMatcherKind.PATH_ADVANCED_PATTERN)
        }
    }
}
