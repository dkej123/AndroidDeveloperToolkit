package dev.acme.adbtoolbox.intellij.logcat

import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import dev.acme.adbtoolbox.domain.diagnostics.NoOpDiagnosticsLog
import dev.acme.adbtoolbox.domain.diagnostics.debug
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

fun interface LogcatScheduledTask {
    fun cancel()
}

fun interface LogcatEdtScheduler {
    fun schedule(task: () -> Unit): LogcatScheduledTask
}

class IntelliJLogcatEdtScheduler : LogcatEdtScheduler {
    override fun schedule(task: () -> Unit): LogcatScheduledTask {
        val cancelled = AtomicBoolean(false)
        ApplicationManager.getApplication().invokeLater(
            { if (!cancelled.get()) task() },
            ModalityState.any(),
        )
        return LogcatScheduledTask { cancelled.set(true) }
    }
}

/** Coalesces renderer updates into bounded EDT work. Behavior follows after failing tests. */
class LogcatEdtBatcher(
    private val target: LogcatVirtualListModel,
    private val scheduler: LogcatEdtScheduler = IntelliJLogcatEdtScheduler(),
    private val maxPendingRows: Int = DEFAULT_MAX_PENDING_ROWS,
    private val maxRowsPerDrain: Int = DEFAULT_MAX_ROWS_PER_DRAIN,
    private val log: DiagnosticsLog = NoOpDiagnosticsLog,
    private val nanoTime: () -> Long = System::nanoTime,
) : Disposable {
    private var lastThroughputReport = Long.MIN_VALUE
    private var reportedMetrics = LogcatRenderingMetrics()
    private val lock = Any()
    private val pendingRows = TreeMap<Long, LogcatRenderRow>()
    private var currentMetrics = LogcatRenderingMetrics()
    private var pendingReset = false
    private var pendingRetainFrom: Long? = null
    private var scheduled = false
    private var scheduledTask: LogcatScheduledTask? = null
    private var disposed = false

    init {
        require(maxPendingRows > 0)
        require(maxRowsPerDrain > 0)
    }

    val metrics: LogcatRenderingMetrics get() = synchronized(lock) { currentMetrics }

    fun submit(batch: LogcatRenderBatch) {
        var scheduleDrain = false
        synchronized(lock) {
            if (disposed) {
                currentMetrics = currentMetrics.copy(ignoredAfterDispose = currentMetrics.ignoredAfterDispose + 1)
                return
            }

            currentMetrics = currentMetrics.copy(
                submittedBatches = currentMetrics.submittedBatches + 1,
                coalescedBatches = currentMetrics.coalescedBatches + if (scheduled) 1 else 0,
            )
            merge(batch)
            enforcePendingLimit()
            currentMetrics = currentMetrics.copy(
                peakPendingRows = maxOf(currentMetrics.peakPendingRows, pendingRows.size),
            )

            if (!scheduled) {
                scheduled = true
                scheduleDrain = true
                currentMetrics = currentMetrics.copy(
                    scheduledEdtTasks = currentMetrics.scheduledEdtTasks + 1,
                )
            }
        }
        if (scheduleDrain) {
            val task = scheduler.schedule(::drain)
            synchronized(lock) {
                if (disposed || !scheduled) {
                    task.cancel()
                } else {
                    scheduledTask = task
                }
            }
        }
    }

    override fun dispose() {
        synchronized(lock) {
            disposed = true
            scheduledTask?.cancel()
            scheduledTask = null
            scheduled = false
            pendingRows.clear()
            pendingReset = false
            pendingRetainFrom = null
        }
    }

    private fun merge(batch: LogcatRenderBatch) {
        if (batch.reset) {
            pendingRows.clear()
            pendingReset = true
            pendingRetainFrom = batch.retainFromSequence
        } else if (batch.retainFromSequence != null) {
            pendingRetainFrom = maxOf(pendingRetainFrom ?: Long.MIN_VALUE, batch.retainFromSequence)
        }

        val retainFrom = pendingRetainFrom
        if (retainFrom != null) pendingRows.keys.removeAll { it < retainFrom }
        batch.rows.forEach { row ->
            if (retainFrom == null || row.sequence >= retainFrom) pendingRows[row.sequence] = row.freeze()
        }
    }

    private fun enforcePendingLimit() {
        var dropped = 0L
        while (pendingRows.size > maxPendingRows) {
            val oldest = pendingRows.pollFirstEntry()?.key ?: break
            pendingRetainFrom = maxOf(pendingRetainFrom ?: Long.MIN_VALUE, oldest + 1)
            dropped++
        }
        if (dropped > 0) {
            currentMetrics = currentMetrics.copy(
                droppedPendingRows = currentMetrics.droppedPendingRows + dropped,
            )
        }
    }

    private fun drain() {
        var scheduleContinuation = false
        synchronized(lock) {
            scheduled = false
            scheduledTask = null
            if (disposed) return
            val drainedRows = ArrayList<LogcatRenderRow>(minOf(maxRowsPerDrain, pendingRows.size))
            val iterator = pendingRows.values.iterator()
            while (iterator.hasNext() && drainedRows.size < maxRowsPerDrain) {
                drainedRows += iterator.next()
                iterator.remove()
            }
            val batch = LogcatRenderBatch(
                rows = drainedRows,
                reset = pendingReset,
                retainFromSequence = pendingRetainFrom,
            )
            pendingReset = false
            pendingRetainFrom = null
            if (pendingRows.isNotEmpty()) {
                scheduled = true
                scheduleContinuation = true
            }
            currentMetrics = currentMetrics.copy(
                appliedBatches = currentMetrics.appliedBatches + 1,
                scheduledEdtTasks = currentMetrics.scheduledEdtTasks + if (scheduleContinuation) 1 else 0,
                peakAppliedRows = maxOf(currentMetrics.peakAppliedRows, drainedRows.size),
            )
            // Applying while holding [lock] prevents dispose from completing between the final
            // disposed check and the model mutation. Java monitors are reentrant, so a model
            // listener may still call submit/dispose on this batcher without deadlocking.
            val start = nanoTime()
            target.apply(batch)
            val end = nanoTime()
            recordDrain(drainedRows.size, (end - start) / 1_000_000, end)
        }
        if (scheduleContinuation) {
            val task = scheduler.schedule(::drain)
            synchronized(lock) {
                if (disposed || !scheduled) {
                    task.cancel()
                } else {
                    scheduledTask = task
                }
            }
        }
    }

    /** Diagnostics: slow EDT drains immediately, and a throughput summary at most once a minute. */
    private fun recordDrain(rows: Int, ms: Long, now: Long) {
        if (ms >= SLOW_DRAIN_MS) {
            log.log(DiagLevel.WARN, DiagCategory.LOGCAT, "slow drain", mapOf("rows" to rows, "ms" to ms, "pending" to pendingRows.size))
        } else {
            log.debug(DiagCategory.LOGCAT, "drain", mapOf("rows" to rows, "ms" to ms, "pending" to pendingRows.size))
        }
        if (lastThroughputReport == Long.MIN_VALUE) {
            lastThroughputReport = now
        } else if (now - lastThroughputReport >= THROUGHPUT_REPORT_NANOS) {
            val metrics = currentMetrics
            log.log(
                DiagLevel.INFO,
                DiagCategory.LOGCAT,
                "throughput",
                mapOf(
                    "seconds" to (now - lastThroughputReport) / 1_000_000_000,
                    "submittedBatches" to metrics.submittedBatches - reportedMetrics.submittedBatches,
                    "edtDrains" to metrics.appliedBatches - reportedMetrics.appliedBatches,
                    "droppedRows" to metrics.droppedPendingRows - reportedMetrics.droppedPendingRows,
                    "peakPendingRows" to metrics.peakPendingRows,
                    "modelSize" to target.getSize(),
                ),
            )
            reportedMetrics = metrics
            lastThroughputReport = now
        }
    }

    companion object {
        private const val SLOW_DRAIN_MS = 50L
        private const val THROUGHPUT_REPORT_NANOS = 60_000_000_000L
        const val DEFAULT_MAX_PENDING_ROWS = 100_000
        const val DEFAULT_MAX_ROWS_PER_DRAIN = 1_000
    }
}
