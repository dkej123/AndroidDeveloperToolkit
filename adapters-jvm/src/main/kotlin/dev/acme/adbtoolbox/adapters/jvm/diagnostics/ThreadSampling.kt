package dev.acme.adbtoolbox.adapters.jvm.diagnostics

import java.lang.management.ManagementFactory

/** Top frames of one sampled thread group; pairs are `frame to sampleCount`, most frequent first. */
data class HotspotReport(
    val samples: Int,
    val selfTop: List<Pair<String, Int>>,
    val inclusiveTop: List<Pair<String, Int>>,
    val states: Map<Thread.State, Int> = emptyMap(),
)

/**
 * Pure aggregation of sampled stacks into hotspot tables (kept separate from sampling so it can be
 * tested with synthetic stacks). The EDT is tracked in every state — a UI freeze is often the EDT
 * BLOCKED on a lock or WAITING on I/O — while the all-threads table only counts RUNNABLE stacks,
 * i.e. real CPU work.
 */
class ThreadSampleAggregator(private val edtThreadPrefix: String = "AWT-EventQueue") {
    private class Table {
        var samples = 0
        val self = HashMap<String, Int>()
        val inclusive = HashMap<String, Int>()
        val states = HashMap<Thread.State, Int>()

        fun add(state: Thread.State, frames: Array<StackTraceElement>) {
            samples++
            states.merge(state, 1, Int::plus)
            if (frames.isEmpty()) return
            self.merge(frames[0].toString(), 1, Int::plus)
            frames.map { it.toString() }.toSet().forEach { inclusive.merge(it, 1, Int::plus) }
        }

        fun report(limit: Int) = HotspotReport(
            samples = samples,
            selfTop = self.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value },
            inclusiveTop = inclusive.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value },
            states = states.toMap(),
        )
    }

    private val edt = Table()
    private val all = Table()
    private var rounds = 0

    fun add(threadName: String, state: Thread.State, frames: Array<StackTraceElement>) {
        if (threadName.startsWith(edtThreadPrefix)) edt.add(state, frames)
        if (state == Thread.State.RUNNABLE) all.add(state, frames)
    }

    fun sampleCompleted() {
        rounds++
    }

    fun edtReport(limit: Int = 30): HotspotReport = edt.report(limit)

    fun allReport(limit: Int = 30): HotspotReport = all.report(limit)

    fun render(durationMs: Long, intervalMs: Long): String = buildString {
        appendLine("ADB Toolbox performance recording")
        appendLine("duration: ${durationMs} ms, interval: ${intervalMs} ms, samples: $rounds")
        appendLine()
        section("EDT ($edtThreadPrefix*) — every state; self = top frame, inclusive = anywhere on the stack", edtReport())
        section("All RUNNABLE threads (CPU work)", allReport())
    }

    private fun StringBuilder.section(title: String, report: HotspotReport) {
        appendLine("== $title")
        appendLine("stack samples: ${report.samples}  states: ${report.states.entries.joinToString { "${it.key}=${it.value}" }}")
        appendLine("-- self (top of stack)")
        report.selfTop.forEach { (frame, count) -> appendLine("%6d  %5.1f%%  %s".format(count, percent(count, report.samples), frame)) }
        appendLine("-- inclusive")
        report.inclusiveTop.forEach { (frame, count) -> appendLine("%6d  %5.1f%%  %s".format(count, percent(count, report.samples), frame)) }
        appendLine()
    }

    private fun percent(count: Int, total: Int): Double = if (total == 0) 0.0 else count * 100.0 / total
}

/**
 * Samples every thread's stack every [intervalMs] for [durationMs] and returns the rendered
 * [ThreadSampleAggregator] report. Blocking — run it on a background thread; [isCancelled] is
 * polled between samples and [onProgress] receives 0.0…1.0.
 */
class ThreadSamplingProfiler(
    private val intervalMs: Long = 200,
    private val durationMs: Long = 60_000,
) {
    fun run(isCancelled: () -> Boolean = { false }, onProgress: (Double) -> Unit = {}): String {
        val threads = ManagementFactory.getThreadMXBean()
        val aggregator = ThreadSampleAggregator()
        val self = Thread.currentThread().id
        val start = System.nanoTime()
        var elapsedMs = 0L
        while (elapsedMs < durationMs && !isCancelled()) {
            threads.dumpAllThreads(false, false)
                .filter { it.threadId != self }
                .forEach { aggregator.add(it.threadName, it.threadState, it.stackTrace) }
            aggregator.sampleCompleted()
            onProgress(elapsedMs.toDouble() / durationMs)
            Thread.sleep(intervalMs)
            elapsedMs = (System.nanoTime() - start) / 1_000_000
        }
        return aggregator.render(elapsedMs, intervalMs)
    }
}
