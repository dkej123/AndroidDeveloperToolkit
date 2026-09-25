package dev.acme.adbtoolbox.application.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** Aggregated timings for one command shape since the previous [CommandStatsCollector.drain]. */
data class CommandStatsSnapshot(
    val key: String,
    val count: Int,
    val failures: Int,
    val totalMs: Long,
    val maxMs: Long,
) {
    val averageMs: Long get() = if (count == 0) 0 else totalMs / count
}

/**
 * Per-command-shape counters (e.g. `adb shell getprop`, serials removed) so routine fast commands
 * can stay out of the INFO log while their cumulative cost still shows up in the periodic stats
 * line — the first thing to read when a user reports that the plugin is slow.
 */
class CommandStatsCollector {
    private val stats = MutableStateFlow<Map<String, CommandStatsSnapshot>>(emptyMap())

    fun record(key: String, durationMs: Long, failed: Boolean) {
        stats.update { current ->
            val previous = current[key] ?: CommandStatsSnapshot(key, 0, 0, 0, 0)
            current + (
                key to previous.copy(
                    count = previous.count + 1,
                    failures = previous.failures + if (failed) 1 else 0,
                    totalMs = previous.totalMs + durationMs,
                    maxMs = maxOf(previous.maxMs, durationMs),
                )
                )
        }
    }

    /** Returns everything recorded since the last call, most expensive first, and resets. */
    fun drain(): List<CommandStatsSnapshot> =
        stats.getAndUpdate { emptyMap() }.values.sortedByDescending { it.totalMs }
}

/** `adb -s R58N shell "getprop ro.x"` → `adb shell getprop`: the executable plus the first two
 * words of the command with any `-s <serial>` pair removed. */
internal fun commandShapeKey(executable: String, arguments: List<String>): String {
    val name = executable.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe")
    val remaining = buildList {
        var skipNext = false
        for (argument in arguments) {
            when {
                skipNext -> skipNext = false
                argument == "-s" -> skipNext = true
                else -> add(argument)
            }
        }
    }
    val words = remaining.flatMap { it.split(' ') }.filter { it.isNotBlank() }.take(2)
    return (listOf(name) + words).joinToString(" ")
}

/** Renders a duration threshold decision shared by the logging decorators. */
internal object SlowThresholds {
    const val INFO_MS = 1_000L
    const val WARN_MS = 5_000L
}
