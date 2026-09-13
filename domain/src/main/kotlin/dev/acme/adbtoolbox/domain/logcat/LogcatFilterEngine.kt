package dev.acme.adbtoolbox.domain.logcat

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.yield

private const val COOPERATION_INTERVAL = 500

/**
 * Holds the currently filtered view of a Logcat buffer's retained history (task 035), keeping it
 * efficient for 10k+ entries by never copying the whole retained history on every appended line:
 * [applyDelta] tests only the newly appended entries against the current criteria and appends the
 * matches, and [evictBefore] trims only the (already small, since eviction removes the oldest
 * first) filtered prefix that fell out of the buffer's retention window. A full re-scan
 * ([setCriteria]) only happens when the criteria themselves change — e.g. the user picks a
 * different minimum level, not on every new line — and is itself cooperatively cancellable so a
 * caller can abandon a large recompute (e.g. the criteria changed again mid-scan).
 */
class LogcatFilterEngine {

    private var criteria = LogcatFilterCriteria()
    private val filtered = ArrayDeque<SequencedLogcatEntry>()

    val currentCriteria: LogcatFilterCriteria get() = criteria

    fun snapshot(): List<SequencedLogcatEntry> = filtered.toList()

    /** Re-filters [source] in full against [newCriteria], replacing the current filtered view. */
    suspend fun setCriteria(newCriteria: LogcatFilterCriteria, source: List<SequencedLogcatEntry>): List<SequencedLogcatEntry> {
        criteria = newCriteria
        filtered.clear()
        val context = currentCoroutineContext()
        source.forEachIndexed { index, sequenced ->
            if (index % COOPERATION_INTERVAL == 0) {
                context.ensureActive()
                yield()
            }
            if (LogcatEntryFilter.matches(sequenced.entry, criteria)) {
                filtered.addLast(sequenced)
            }
        }
        return filtered.toList()
    }

    /** Tests only [newEntries] against the current criteria and appends the matches. */
    fun applyDelta(newEntries: List<SequencedLogcatEntry>): List<SequencedLogcatEntry> {
        if (newEntries.isEmpty()) return emptyList()
        val appended = mutableListOf<SequencedLogcatEntry>()
        for (sequenced in newEntries) {
            if (LogcatEntryFilter.matches(sequenced.entry, criteria)) {
                filtered.addLast(sequenced)
                appended += sequenced
            }
        }
        return appended
    }

    /** Drops filtered entries older than [oldestRetainedSequence], mirroring buffer eviction. */
    fun evictBefore(oldestRetainedSequence: Long) {
        while (filtered.isNotEmpty() && filtered.first().sequence < oldestRetainedSequence) {
            filtered.removeFirst()
        }
    }
}
