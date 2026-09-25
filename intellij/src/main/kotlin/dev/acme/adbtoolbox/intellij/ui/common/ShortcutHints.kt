package dev.acme.adbtoolbox.intellij.ui.common

import com.intellij.openapi.keymap.KeymapUtil
import javax.swing.KeyStroke

/**
 * Tooltip shortcut hints rendered from the active keymap, so users see the chord that actually
 * works on their platform (Ctrl+Shift+D on Linux/Windows, ⇧⌘D on macOS). The design's "⇧⌘…"
 * notation is macOS-specific and must never be hardcoded.
 */
object ShortcutHints {
    fun withAction(label: String, actionId: String): String {
        val shortcut = runCatching { KeymapUtil.getFirstKeyboardShortcutText(actionId) }.getOrDefault("")
        return if (shortcut.isBlank()) label else "$label  $shortcut"
    }

    fun withKeyStroke(label: String, keyStroke: KeyStroke): String = "$label  ${KeymapUtil.getKeystrokeText(keyStroke)}"
}
