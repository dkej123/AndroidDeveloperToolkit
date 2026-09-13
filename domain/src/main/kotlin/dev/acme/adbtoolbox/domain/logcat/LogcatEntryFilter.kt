package dev.acme.adbtoolbox.domain.logcat

/**
 * Pure severity/pid/search predicate over one immutable [LogcatEntry] (task 035). Every active
 * criterion composes with AND semantics. [LogcatEntry.DaemonMarker] and [LogcatEntry.Malformed]
 * carry no severity or pid, so an active [LogcatFilterCriteria.minSeverity] or
 * [LogcatFilterCriteria.pids] excludes them — only [LogcatEntry.Record] can satisfy those criteria.
 */
object LogcatEntryFilter {

    fun matches(entry: LogcatEntry, criteria: LogcatFilterCriteria): Boolean {
        val record = (entry as? LogcatEntry.Record)?.record

        if (criteria.minSeverity != null && (record == null || record.severity < criteria.minSeverity)) {
            return false
        }
        if (criteria.pids != null && (record == null || record.pid !in criteria.pids)) {
            return false
        }
        if (!criteria.isSearching) return true

        return containsQuery(entry, criteria.query)
    }

    private fun containsQuery(entry: LogcatEntry, query: String): Boolean = when (entry) {
        is LogcatEntry.Record ->
            entry.record.tag.value.contains(query, ignoreCase = true) ||
                entry.record.message.value.contains(query, ignoreCase = true) ||
                entry.continuationLines.any { it.contains(query, ignoreCase = true) }

        is LogcatEntry.DaemonMarker -> entry.raw.contains(query, ignoreCase = true)
        is LogcatEntry.Malformed -> entry.raw.contains(query, ignoreCase = true)
    }
}
