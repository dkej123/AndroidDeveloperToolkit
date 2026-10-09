package io.github.dkej123.devicecockpit.domain.settings

/** A persisted settings field whose change makes derived runtime state stale. */
enum class SettingsDependency {
    AdbPath,
    ScrcpyPath,
    CaptureDirectory,
    LogcatBufferSize,
}

/** Invalidates only the runtime dependencies affected by an accepted settings change. */
fun interface SettingsInvalidationPort {
    suspend fun invalidate(changed: Set<SettingsDependency>)
}
