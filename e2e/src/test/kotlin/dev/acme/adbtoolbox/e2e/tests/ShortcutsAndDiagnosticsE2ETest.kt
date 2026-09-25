package dev.acme.adbtoolbox.e2e.tests

import com.intellij.remoterobot.utils.keyboard
import dev.acme.adbtoolbox.e2e.infra.E2eConfig
import dev.acme.adbtoolbox.e2e.infra.E2eTest
import dev.acme.adbtoolbox.e2e.infra.View
import dev.acme.adbtoolbox.e2e.infra.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.io.File
import java.time.Duration
import java.util.zip.ZipFile

/** Global shortcuts from plugin.xml, their on-screen hints, and the diagnostics actions. */
class ShortcutsAndDiagnosticsE2ETest : E2eTest() {

    @Test
    fun `Ctrl+Shift+L focuses the Logcat view`() {
        studio.navigate(View.Device)
        focusEditorArea()

        studio.robot.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_L) }

        awaitUntil(Duration.ofSeconds(10), Duration.ofMillis(200), "the Logcat view") { studio.isShowing("//div[@class='LogcatPanel']") }
    }

    @Test
    fun `Ctrl+Shift+M toggles mirroring`() {
        studio.navigate(View.Device)
        focusEditorArea()

        studio.robot.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_M) }

        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy to start") { scrcpyRunning() }
        studio.robot.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_M) }
        awaitUntil(E2eConfig.deviceTimeout(15), Duration.ofMillis(500), "scrcpy to stop") { !scrcpyRunning() }
    }

    @Test
    fun `shortcut hints use this platform's modifier keys`() {
        // Tooltips must name the keys of the active keymap (Ctrl+Shift+… on Linux/Windows), not ⌘.
        studio.navigate(View.Device)
        val tooltips: String = studio.toolWindow().callJs(
            """
            var out = [];
            var jc = java.lang.Class.forName("javax.swing.JComponent");
            function walk(c) {
                if (jc.isInstance(c) && c.getToolTipText() != null) out.push(c.getToolTipText());
                var ch = c.getComponents(); for (var i = 0; i < ch.length; i++) walk(ch[i]);
            }
            walk(component);
            out.join("\n")
            """.trimIndent(),
            true,
        )
        if (!studio.robot.isMac()) tooltips.lines().filter { '⌘' in it }.shouldBeEmpty()
    }

    @Test
    fun `Collect Diagnostics writes a bundle with logs and adb state`() {
        val desktop = File(System.getProperty("user.home"), "Desktop").apply { mkdirs() }
        val before = desktop.list().orEmpty().toSet()

        studio.invokeAction("AdbToolbox.CollectDiagnostics")

        var bundle: File? = null
        awaitUntil(Duration.ofSeconds(60), Duration.ofMillis(500), "a diagnostics ZIP") {
            bundle = desktop.listFiles().orEmpty().firstOrNull { it.name !in before && it.name.startsWith("adb-toolbox-diagnostics") && it.name.endsWith(".zip") }
            bundle != null
        }
        Thread.sleep(1000)
        val entries = ZipFile(bundle!!).use { zip -> zip.entries().toList().map { it.name } }
        bundle!!.delete()
        for (required in listOf("environment.txt", "adb.txt", "threads.txt")) {
            check(entries.any { it.endsWith(required) }) { "$required missing from $entries" }
        }
        check(entries.any { "adb-toolbox.log" in it }) { "plugin log missing from $entries" }
    }

    private fun focusEditorArea() {
        studio.robot.runJs(
            dev.acme.adbtoolbox.e2e.infra.Studio.PROJECT +
                "com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project).getRootPane(), true);",
            true,
        )
        Thread.sleep(300)
    }

    private fun scrcpyRunning(): Boolean =
        ProcessHandle.allProcesses().anyMatch { it.isAlive && it.info().command().orElse("").endsWith("/scrcpy") }
}
