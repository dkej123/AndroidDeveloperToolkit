package io.github.dkej123.devicecockpit.intellij.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeviceCockpitSettingsRegistrationTest : BasePlatformTestCase() {

    fun `test Device Cockpit settings are registered as a native project configurable`() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()

        assertTrue(
            "Missing project-scoped Device Cockpit Configurable registration",
            Regex(
                """<projectConfigurable[^>]*parentId="tools"[^>]*instance="io\.github\.dkej123\.devicecockpit\.intellij\.settings\.DeviceCockpitSettingsConfigurable"[^>]*id="dev\.acme\.adbtoolbox\.settings"[^>]*displayName="Device Cockpit"""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(pluginXml),
        )
    }
}
