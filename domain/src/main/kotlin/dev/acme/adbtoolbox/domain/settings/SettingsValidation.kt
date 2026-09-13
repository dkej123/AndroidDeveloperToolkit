package dev.acme.adbtoolbox.domain.settings

import dev.acme.adbtoolbox.domain.discovery.ExecutableFileProbe

/** Why a candidate [SettingsState] was rejected by [validateSettings] — "invalid paths cannot
 * silently apply" (task 038's acceptance criterion): every rejection is reported, never dropped. */
sealed interface SettingsFieldError {
    data object AdbPathNotExecutable : SettingsFieldError
    data object ScrcpyPathNotExecutable : SettingsFieldError
    data object CaptureDirectoryInvalid : SettingsFieldError
    data object LogcatBufferSizeOutOfRange : SettingsFieldError
}

sealed interface SettingsValidationResult {
    data class Valid(val state: SettingsState) : SettingsValidationResult
    data class Invalid(val errors: Set<SettingsFieldError>) : SettingsValidationResult
}

/**
 * Validates [candidate] before it is ever persisted: a blank/`null` path override or capture
 * directory is always accepted (falls back to discovery/no override), but a non-blank one must
 * resolve to a real, executable file ([executableProbe]) or a real directory ([directoryProbe]);
 * [SettingsState.logcatBufferSizeKb] must fall inside
 * [SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB]..[SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB]. Collects
 * every failing field rather than stopping at the first, so a caller (the Configurable) can report
 * all of them at once.
 */
suspend fun validateSettings(
    candidate: SettingsState,
    executableProbe: ExecutableFileProbe,
    directoryProbe: DirectoryProbe,
): SettingsValidationResult {
    val normalized = candidate.copy(
        adbPathOverride = candidate.adbPathOverride.normalizeOptionalPath(),
        scrcpyPathOverride = candidate.scrcpyPathOverride.normalizeOptionalPath(),
        captureDirectory = candidate.captureDirectory.normalizeOptionalPath(),
    )
    val errors = mutableSetOf<SettingsFieldError>()

    normalized.adbPathOverride?.let { path ->
        if (!executableProbe.isExecutable(path)) errors += SettingsFieldError.AdbPathNotExecutable
    }
    normalized.scrcpyPathOverride?.let { path ->
        if (!executableProbe.isExecutable(path)) errors += SettingsFieldError.ScrcpyPathNotExecutable
    }
    normalized.captureDirectory?.let { path ->
        if (!directoryProbe.isValidDirectory(path)) errors += SettingsFieldError.CaptureDirectoryInvalid
    }
    if (normalized.logcatBufferSizeKb !in SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB..SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB) {
        errors += SettingsFieldError.LogcatBufferSizeOutOfRange
    }

    return if (errors.isEmpty()) {
        SettingsValidationResult.Valid(normalized)
    } else {
        SettingsValidationResult.Invalid(errors)
    }
}

private fun String?.normalizeOptionalPath(): String? = this?.trim()?.takeIf(String::isNotEmpty)
