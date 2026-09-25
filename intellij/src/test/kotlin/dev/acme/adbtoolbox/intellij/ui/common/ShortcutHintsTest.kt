package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class ShortcutHintsTest : BasePlatformTestCase() {

    fun `test an action hint shows the active keymap's shortcut, not a hardcoded Mac chord`() {
        // Regression (docs/e2e-testing.md): tooltips said "⌘⇧D" on Linux/Windows, where the
        // keymap binds Ctrl+Shift+D.
        val hint = ShortcutHints.withAction("Refresh device list", "AdbToolbox.RefreshDevices")

        assertEquals("Refresh device list  " + KeymapUtil.getFirstKeyboardShortcutText("AdbToolbox.RefreshDevices"), hint)
        if (!SystemInfo.isMac) assertFalse(hint, '⌘' in hint)
    }

    fun `test an action without a shortcut yields just the label`() {
        assertEquals("Open", ShortcutHints.withAction("Open", "AdbToolbox.NoSuchAction"))
    }

    fun `test a keystroke hint uses the platform's modifier names`() {
        val hint = ShortcutHints.withKeyStroke("Search log…", KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK))

        assertEquals("Search log…  " + KeymapUtil.getKeystrokeText(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK)), hint)
    }
}
