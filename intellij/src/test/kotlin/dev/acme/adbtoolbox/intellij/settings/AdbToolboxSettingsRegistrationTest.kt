package dev.acme.adbtoolbox.intellij.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AdbToolboxSettingsRegistrationTest : BasePlatformTestCase() {

    fun `test ADB Toolbox settings are registered as a native project configurable`() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()

        assertTrue(
            "Missing project-scoped ADB Toolbox Configurable registration",
            Regex(
                """<projectConfigurable[^>]*parentId="tools"[^>]*instance="dev\.acme\.adbtoolbox\.intellij\.settings\.AdbToolboxSettingsConfigurable"[^>]*id="dev\.acme\.adbtoolbox\.settings"[^>]*displayName="ADB Toolbox"""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(pluginXml),
        )
    }
}
