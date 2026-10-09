package io.github.dkej123.devicecockpit.intellij.terminal

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deviceactions.ShellSessionIntent
import io.github.dkej123.devicecockpit.domain.deviceactions.TerminalLaunchResult
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * [TerminalLauncherAdapter]'s Terminal-plugin-absent path (ADR 0007: "a fixable error toast ...
 * never a silent no-op"). Kept as a [BasePlatformTestCase] like every other `:intellij` adapter/
 * coordinator test in this module. The plugin-present, real-`TerminalToolWindowManager` path is
 * not covered here: it would spawn a real local shell process/tool-window content in this headless
 * sandbox for no behavioral assertion this module's own code controls (the `terminalPluginPresent`
 * boolean itself is the composition-time decision task 016 owns, exercised directly by
 * [io.github.dkej123.devicecockpit.intellij.composition.AdbToolboxProjectServiceTest]) — mirroring
 * `AdbToolboxProjectServiceTest`'s documented `dispatchers.main` EDT-round-trip gap for this
 * sandbox.
 */
class TerminalLauncherAdapterTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    fun `test when the Terminal plugin is absent openShell returns Unavailable without touching any Terminal-plugin class`() {
        val adapter = TerminalLauncherAdapter(project, TestDispatchers(), terminalPluginPresent = false)
        val intent = ShellSessionIntent(DeviceSerial.of("emulator-5554"), "/opt/homebrew/bin/adb")

        val result = runBlocking { adapter.openShell(intent) }

        assertTrue(result is TerminalLaunchResult.Unavailable)
        assertEquals(
            "Open shell requires the Terminal plugin",
            (result as TerminalLaunchResult.Unavailable).reason,
        )
    }

    fun `test the reflectively called createShellWidget exists with the expected signature and return type`() {
        // createShellTab looks the method up by name; a renamed/reshaped platform method must fail
        // here rather than only at runtime in the user's IDE.
        val method = org.jetbrains.plugins.terminal.TerminalToolWindowManager::class.java.getMethod(
            "createShellWidget",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )

        assertTrue(com.intellij.terminal.ui.TerminalWidget::class.java.isAssignableFrom(method.returnType))
    }
}
