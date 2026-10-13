package io.github.dkej123.devicecockpit.intellij.apps.details

import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkVariant
import io.github.dkej123.devicecockpit.domain.deeplinks.ParameterLocation
import io.github.dkej123.devicecockpit.domain.deeplinks.ParameterRequirement
import io.github.dkej123.devicecockpit.domain.deeplinks.VariantParameter
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DeepLinkLauncherPanelTest : BasePlatformTestCase() {
    private val opened = mutableListOf<String>()
    private val copied = mutableListOf<String>()

    private val variant = DeepLinkVariant(
        componentName = "com.acme.MainActivity",
        template = "app://acme/user/{userId}?tab={tab}",
        parameters = listOf(
            VariantParameter("userId", ParameterLocation.PATH, ParameterRequirement.REQUIRED, type = "long"),
            VariantParameter("tab", ParameterLocation.QUERY, ParameterRequirement.OPTIONAL, defaultValue = "info"),
        ),
        fromNavigation = true,
    )

    fun `test parameters get fields, extras are appended and the link opens only when complete`() {
        val panel = DeepLinkLauncherPanel(onOpen = { opened += it }, onCopy = { copied += it })
        panel.show(variant, "com.acme")

        assertEquals("app://acme/user/{userId}?tab=info", panel.previewForTest)
        assertEquals("Fill in userId to open it.", panel.missingForTest)
        assertFalse(panel.openForTest.isEnabled)

        panel.fieldForTest("userId").text = "42"
        panel.addExtraForTest("utm_source", "cockpit")

        assertEquals("app://acme/user/42?tab=info&utm_source=cockpit", panel.previewForTest)
        assertTrue(panel.openForTest.isEnabled)
        panel.openForTest.doClick()
        assertEquals(listOf("app://acme/user/42?tab=info&utm_source=cockpit"), opened)
    }

    fun `test typed values survive picking another variant with the same parameter`() {
        val panel = DeepLinkLauncherPanel(onOpen = {}, onCopy = {})
        panel.show(variant, "com.acme")
        panel.fieldForTest("userId").text = "7"

        panel.show(variant.copy(template = "app://acme/profile/{userId}"), "com.acme")

        assertEquals("app://acme/profile/7", panel.previewForTest)
    }
}
