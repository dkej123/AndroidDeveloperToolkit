package dev.acme.adbtoolbox.intellij.logcat

import dev.acme.adbtoolbox.domain.logcat.LogSeverity

data class LogcatMatchSpan(val startInclusive: Int, val endExclusive: Int) {
    init {
        require(startInclusive >= 0)
        require(endExclusive > startInclusive)
    }
}

data class LogcatRenderRow(
    val sequence: Long,
    val severity: LogSeverity?,
    val timestamp: String?,
    val tag: String?,
    val message: String,
    val matchSpans: List<LogcatMatchSpan> = emptyList(),
)

data class LogcatColumnVisibility(
    val timestamp: Boolean = true,
    val tag: Boolean = true,
)

data class LogcatRenderPresentation(
    val wrapLines: Boolean = false,
    val columns: LogcatColumnVisibility = LogcatColumnVisibility(),
)

data class LogcatRenderBatch(
    val rows: List<LogcatRenderRow>,
    val reset: Boolean = false,
    val retainFromSequence: Long? = null,
)

data class LogcatRenderingMetrics(
    val submittedBatches: Long = 0,
    val scheduledEdtTasks: Long = 0,
    val appliedBatches: Long = 0,
    val coalescedBatches: Long = 0,
    val peakPendingRows: Int = 0,
    val peakAppliedRows: Int = 0,
    val droppedPendingRows: Long = 0,
    val ignoredAfterDispose: Long = 0,
)
