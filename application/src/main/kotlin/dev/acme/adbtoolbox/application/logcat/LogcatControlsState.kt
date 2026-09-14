package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.logcat.LogSeverity
import dev.acme.adbtoolbox.domain.logcat.LogcatPauseState
import dev.acme.adbtoolbox.domain.logcat.LogcatSessionState
import dev.acme.adbtoolbox.domain.logcat.LogcatUnseenState

/**
 * Task 037's Logcat controls/presentation state — everything `design/README.md` §7's toolbar,
 * filter row, paused pill, footer, and empty states need, minus the actual rendered rows (those stay
 * in [LogcatControlsController.filterUpdates]/[LogcatControlsController.snapshot] as plain
 * [dev.acme.adbtoolbox.domain.logcat.SequencedLogcatEntry] lists, so this state never copies the
 * full retained/filtered history on every appended line).
 *
 * [minSeverity]/[packageFilterOn]/[wrap] are the persisted slice (task 037,
 * [dev.acme.adbtoolbox.domain.logcat.LogcatPersistedControls]); [query]/[pauseState]/[follow] are
 * runtime-only, matching the design's state model split of persisted vs. runtime fields.
 * [packageFilterLabel] is the Apps-selected package name, or `null` when none is selected ("all
 * packages"). [unseen] is [LogcatUnseenState] extended to also apply while merely un-followed
 * (autoscroll off) — see [LogcatControlsController] for the derivation.
 */
data class LogcatControlsState(
    val serial: DeviceSerial? = null,
    val isDeviceEligible: Boolean = false,
    val sessionState: LogcatSessionState = LogcatSessionState.Idle,
    val query: String = "",
    val minSeverity: LogSeverity? = null,
    val packageFilterOn: Boolean = true,
    val packageFilterLabel: String? = null,
    val wrap: Boolean = false,
    val pauseState: LogcatPauseState = LogcatPauseState.Resumed,
    val follow: Boolean = true,
    val unseen: LogcatUnseenState = LogcatUnseenState(paused = false, unseenCount = 0, viewTruncated = false),
    val visibleCount: Int = 0,
    val totalRetainedCount: Int = 0,
    val totalBytes: Int = 0,
    val cleared: Boolean = false,
    val error: String? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()

    val isFiltered: Boolean
        get() = minSeverity != null || packageFilterOn || isSearching

    val hasDevice: Boolean get() = serial != null

    val isFilteredEmpty: Boolean
        get() = hasDevice && !cleared && totalRetainedCount > 0 && visibleCount == 0 && isFiltered

    /** `design/README.md` §7's filtered-empty subtitle, e.g. "Level is E and above, limited to
     * com.acme.shop, matching “timeout”." — `null` when nothing is currently filtering. */
    val filterSummary: String?
        get() {
            if (!isFiltered) return null
            val parts = buildList {
                minSeverity?.let { add("Level is ${it.name.first()} and above") }
                if (packageFilterOn && packageFilterLabel != null) add("limited to $packageFilterLabel")
                if (isSearching) add("matching “$query”")
            }
            return if (parts.isEmpty()) null else parts.joinToString(", ")
        }
}
