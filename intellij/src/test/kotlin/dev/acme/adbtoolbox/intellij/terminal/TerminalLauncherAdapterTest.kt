package dev.acme.adbtoolbox.intellij.terminal

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.deviceactions.ShellSessionIntent
import dev.acme.adbtoolbox.domain.deviceactions.TerminalLaunchResult
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * [TerminalLauncherAdapter]'s Terminal-plugin-absent path (ADR 0007: "a fixable error toast ...
 * never a silent no-op"). Kept as a [BasePlatformTestCase] like every other `:intellij` adapter/
 * coordinator test in this module. The plugin-present, real-`TerminalToolWindowManager` path is
 * not covered here: it would spawn a real local shell process/tool-window content in this headless
 * sandbox for no behavioral assertion this module's own code controls (the `terminalPluginPresent`
 * boolean itself is the composition-time decision task 016 owns, exercised directly by
 * [dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectServiceTest]) — mirroring
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
}
