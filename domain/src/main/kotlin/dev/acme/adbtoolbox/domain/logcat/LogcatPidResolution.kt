package dev.acme.adbtoolbox.domain.logcat

/**
 * The outcome of resolving the process id(s) for a selected package (task 035's `pidof`-based
 * package filter, `design/IMPLEMENTATION.md` §4: "filter by pid (survives restarts by
 * re-resolving)"). [NotResolved] is the initial/no-selection state, distinct from [NoProcess] (a
 * package is selected but is not currently running) so a caller can tell "nothing to resolve yet"
 * apart from "resolved: not running". [Resolved] carries a set because some packages run multiple
 * processes (e.g. `android:process` attributes) and `pidof` can report more than one pid.
 */
sealed interface LogcatPidResolution {
    data object NotResolved : LogcatPidResolution
    data object NoProcess : LogcatPidResolution
    data class Resolved(val pids: Set<ProcessId>) : LogcatPidResolution
    data class Malformed(val raw: String) : LogcatPidResolution
    data class Failed(val reason: String) : LogcatPidResolution
}
