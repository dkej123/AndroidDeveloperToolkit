package dev.acme.adbtoolbox.domain.logcat

/**
 * Client-side Logcat filter criteria (task 035): [minSeverity] is "this level and above" (`null`
 * disables the floor), [query] is a case-insensitive substring search over tag/message/continuation
 * text (blank disables it), and [pids] restricts to records emitted by one of the given process ids
 * (`null` disables package filtering — distinct from an empty set, which would match nothing).
 * [pids] is populated from [LogcatPidResolution] rather than a bare package name so re-resolving the
 * selected package's pid after an app restart only changes this one field — the severity/query
 * fields are untouched by that re-resolution.
 */
data class LogcatFilterCriteria(
    val minSeverity: LogSeverity? = null,
    val query: String = "",
    val pids: Set<ProcessId>? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}
