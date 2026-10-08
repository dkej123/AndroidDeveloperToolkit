package dev.acme.adbtoolbox.intellij.host

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId

/** Registered only by `adb-toolbox-android.xml`, i.e. only while the Android plugin is loaded. */
class AndroidPluginMarker

/** Registered only by `adb-toolbox-terminal.xml`, i.e. only while the Terminal plugin is loaded. */
class TerminalPluginMarker

/**
 * Plugin facts through API that is public across the whole supported range (242+):
 * `PluginManagerCore.getPlugin`/`loadedPlugins` and `PluginManager.findEnabledPlugin` are internal
 * from 2026.x on, while their replacements (`isLoaded(PluginId)`, `PluginDetailsService`) do not
 * exist on the 242 baseline. Whether an optional dependency is usable is answered the way the
 * platform intends: its `config-file` — and the marker service in it — is only loaded with it.
 */
object InstalledPlugins {
    const val ANDROID = "org.jetbrains.android"
    const val TERMINAL = "org.jetbrains.plugins.terminal"

    /** The Android plugin is loaded (installed, enabled and its dependencies resolved). */
    val androidLoaded: Boolean get() = isRegistered(AndroidPluginMarker::class.java)

    /** The Terminal plugin is loaded. */
    val terminalLoaded: Boolean get() = isRegistered(TerminalPluginMarker::class.java)

    /** "loaded" / "installed, not loaded" / "installed, disabled" / "not installed" — for diagnostics. */
    fun describe(id: String): String {
        val pluginId = PluginId.getId(id)
        val loaded = when (id) {
            ANDROID -> androidLoaded
            TERMINAL -> terminalLoaded
            else -> false
        }
        return when {
            loaded -> "loaded"
            !PluginManagerCore.isPluginInstalled(pluginId) -> "not installed"
            PluginManagerCore.isDisabled(pluginId) -> "installed, disabled"
            else -> "installed, not loaded"
        }
    }

    /** This plugin's own version, read from the class loader that loaded it; `null` outside the IDE (tests). */
    fun ownVersion(): String? = (InstalledPlugins::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version

    private fun isRegistered(marker: Class<*>): Boolean =
        ApplicationManager.getApplication()?.getService(marker) != null
}
