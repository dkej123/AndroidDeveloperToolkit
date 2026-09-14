package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.logcat.LogSeverity

/** User-triggered inputs [LogcatControlsController] reduces against [LogcatControlsState] (ADR 0004). */
sealed interface LogcatControlsIntent {

    /** Live search-field text as the user types (`design/README.md` §7's toolbar search field). */
    data class SetQuery(val text: String) : LogcatControlsIntent

    /** Picks the minimum-severity level chip; `null` clears the floor ("V and above" == no filter). */
    data class SetMinSeverity(val level: LogSeverity?) : LogcatControlsIntent

    /** Toggles the package-filter chip between the Apps-selected package's pid(s) and "all packages". */
    data object TogglePackageFilter : LogcatControlsIntent

    /** Toggles wrapped-line presentation. */
    data object ToggleWrap : LogcatControlsIntent

    /** Toggles paused/resumed (toolbar Pause button, or the Space shortcut). */
    data object TogglePause : LogcatControlsIntent

    /** The user scrolled away from the newest line — turns follow (autoscroll) off. */
    data object ManualScrollAway : LogcatControlsIntent

    /** Toolbar Autoscroll toggle — flips follow on/off directly, independent of pause. */
    data object ToggleFollow : LogcatControlsIntent

    /** Resumes following the newest line, catching the pause point up too (toolbar "Jump to latest",
     * or the End shortcut, per `design/README.md`'s "End resumes autoscroll and jumps to the newest line"). */
    data object JumpToLatest : LogcatControlsIntent

    /** Clears only the local retained/filtered view — never issues a device-side `logcat -c`. */
    data object ClearLocal : LogcatControlsIntent

    /** The filtered-empty state's "Reset filters" link: clears level/package-filter/query together. */
    data object ResetFilters : LogcatControlsIntent
}
