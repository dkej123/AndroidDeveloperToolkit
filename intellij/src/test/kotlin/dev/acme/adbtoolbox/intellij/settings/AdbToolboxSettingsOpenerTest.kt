package dev.acme.adbtoolbox.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AdbToolboxSettingsOpenerTest : BasePlatformTestCase() {

    fun `test opens Settings on the plugin's own page, selected by configurable class`() {
        // Regression (docs/e2e-testing.md): showSettingsDialog(project, String) selects a page by
        // its *display name*; passing the configurable id opened Settings on "Appearance".
        var opened: Pair<Project, Class<out Configurable>>? = null

        AdbToolboxSettingsOpener.open(project) { target, configurable -> opened = target to configurable }

        assertSame(project, opened?.first)
        assertEquals(AdbToolboxSettingsConfigurable::class.java, opened?.second)
    }
}
