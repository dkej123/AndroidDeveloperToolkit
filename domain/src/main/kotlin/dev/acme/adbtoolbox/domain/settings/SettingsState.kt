package dev.acme.adbtoolbox.domain.settings

/**
 * The four user-editable settings this task's Configurable persists (ADR 0006: project scope for
 * every field, since none of them is legitimately IDE-wide today): an approved override path for
 * `adb`/`scrcpy` (falls back to normal discovery, task 004, when `null`), the capture directory,
 * and the local Logcat ring-buffer size. Structurally always valid by construction — out-of-range
 * or unreachable values are rejected by [validateSettings] before ever reaching this type via a
 * persisted write, so a caller holding a [SettingsState] never needs to re-check it.
 */
data class SettingsState(
    val adbPathOverride: String? = null,
    val scrcpyPathOverride: String? = null,
    val captureDirectory: String? = null,
    val logcatBufferSizeKb: Int = DEFAULT_LOGCAT_BUFFER_SIZE_KB,
) {
    companion object {
        /** `design/designs/ADB Toolbox IA.dc.html`'s Logcat footer default: "buffer 16 MB". */
        const val DEFAULT_LOGCAT_BUFFER_SIZE_KB: Int = 16 * 1024
        const val MIN_LOGCAT_BUFFER_SIZE_KB: Int = 64
        const val MAX_LOGCAT_BUFFER_SIZE_KB: Int = 64 * 1024

        val DEFAULT: SettingsState = SettingsState()
    }
}
