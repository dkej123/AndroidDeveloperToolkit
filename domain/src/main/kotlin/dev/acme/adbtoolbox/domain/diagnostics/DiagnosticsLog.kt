package dev.acme.adbtoolbox.domain.diagnostics

/** Severity of a [DiagnosticsLog] entry, lowest first. */
enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

/**
 * The plugin's diagnostics log port: a structured, append-only record of what the plugin did
 * (commands, transports, discovery, device changes, UI stalls) that a user can export and send
 * when something misbehaves on their machine. Implementations must never block the caller —
 * logging is called from the EDT and from hot process-output paths.
 *
 * [fields] are rendered as `key=value` pairs; values are rendered with `toString()`.
 */
interface DiagnosticsLog {
    fun isEnabled(level: DiagLevel): Boolean

    fun log(
        level: DiagLevel,
        category: String,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    )
}

fun DiagnosticsLog.debug(category: String, message: String, fields: Map<String, Any?> = emptyMap()) {
    if (isEnabled(DiagLevel.DEBUG)) log(DiagLevel.DEBUG, category, message, fields)
}

fun DiagnosticsLog.info(category: String, message: String, fields: Map<String, Any?> = emptyMap()) =
    log(DiagLevel.INFO, category, message, fields)

fun DiagnosticsLog.warn(category: String, message: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
    log(DiagLevel.WARN, category, message, fields, error)

fun DiagnosticsLog.error(category: String, message: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
    log(DiagLevel.ERROR, category, message, fields, error)

/** Stable category names, so exported logs can be filtered with `grep '\[adb\]'` etc. */
object DiagCategory {
    const val PROCESS = "process"
    const val ADB = "adb"
    const val DISCOVERY = "discovery"
    const val DEVICE = "device"
    const val FEEDBACK = "feedback"
    const val EDT = "ui.edt"
    const val LOGCAT = "logcat"
    const val PERF = "perf"
    const val LIFECYCLE = "lifecycle"
}

/** Discards everything; the default where no diagnostics sink is wired (tests, previews). */
object NoOpDiagnosticsLog : DiagnosticsLog {
    override fun isEnabled(level: DiagLevel): Boolean = false

    override fun log(level: DiagLevel, category: String, message: String, fields: Map<String, Any?>, error: Throwable?) = Unit
}
