package dev.acme.adbtoolbox.intellij.host

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InstalledPluginsTest : BasePlatformTestCase() {

    fun `test a loaded optional dependency is reported loaded through its marker service`() {
        // The Terminal plugin is a bundled test dependency of this module and loads in the sandbox.
        assertTrue(InstalledPlugins.terminalLoaded)
        assertEquals("loaded", InstalledPlugins.describe(InstalledPlugins.TERMINAL))
    }

    fun `test an installed plugin that did not load is not reported usable`() {
        // The Android plugin is on the test classpath but cannot load without its own dependencies;
        // installed-and-not-disabled alone must not make the composition use its APIs.
        assertFalse(InstalledPlugins.androidLoaded)
        assertFalse(InstalledPlugins.describe(InstalledPlugins.ANDROID) == "loaded")
    }

    fun `test the own version is absent when the plugin is not loaded by a plugin class loader`() {
        // Unit tests load this module from the test classpath, not a PluginClassLoader.
        assertNull(InstalledPlugins.ownVersion())
    }
}
