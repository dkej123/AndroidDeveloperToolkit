package dev.acme.adbtoolbox.application.settings

sealed interface SettingsIntent {
    data class UpdateAdbPath(val value: String?) : SettingsIntent
    data class UpdateScrcpyPath(val value: String?) : SettingsIntent
    data class UpdateCaptureDirectory(val value: String?) : SettingsIntent
    data class UpdateLogcatBufferSizeKb(val value: Int) : SettingsIntent
    data object Apply : SettingsIntent
    data object Reset : SettingsIntent
    data object RetryLoad : SettingsIntent
}
