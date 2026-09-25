package dev.acme.adbtoolbox.e2e.infra

import com.intellij.remoterobot.RemoteRobot
import java.io.File

/**
 * Measures the IDE process from the outside: per-thread CPU from `/proc/<pid>/task/<tid>/stat`
 * (Linux) and UI-thread responsiveness through the robot. These are the numbers a user feels as
 * "the IDE is slow", independent of the plugin's own diagnostics.
 */
object IdeProbes {
    data class ThreadCpu(val name: String, val percentOfOneCore: Double)

    data class CpuSample(val seconds: Double, val totalPercent: Double, val threads: List<ThreadCpu>) {
        /** Sum over threads whose name starts with [prefix] (thread names are cut to 15 chars). */
        fun percent(prefix: String): Double = threads.filter { it.name.startsWith(prefix) }.sumOf { it.percentOfOneCore }
        fun top(n: Int = 8): String = threads.sortedByDescending { it.percentOfOneCore }.take(n)
            .joinToString { "${it.name}=${"%.0f".format(it.percentOfOneCore)}%" }
    }

    data class EdtLatency(val samples: Int, val maxMillis: Double, val p95Millis: Double, val meanMillis: Double)

    fun studioPid(): Long = ProcessHandle.allProcesses()
        .filter { handle -> handle.info().command().orElse("").startsWith(File(E2eConfig.studioHome, "jbr/bin/java").path) }
        .map { it.pid() }
        .findFirst()
        .orElseThrow { IllegalStateException("Android Studio process not found under ${E2eConfig.studioHome}") }

    /** CPU used by each IDE thread over [seconds], as a percentage of one core. */
    fun sampleCpu(seconds: Double, pid: Long = studioPid()): CpuSample {
        val before = readThreadTicks(pid)
        Thread.sleep((seconds * 1000).toLong())
        val after = readThreadTicks(pid)
        val threads = after.mapNotNull { (tid, current) ->
            val previous = before[tid] ?: return@mapNotNull null
            ThreadCpu(current.first, (current.second - previous.second) / CLOCK_TICKS / seconds * 100)
        }
        return CpuSample(seconds, threads.sumOf { it.percentOfOneCore }, threads)
    }

    /**
     * Round-trips an empty task through the EDT [samples] times (every [intervalMillis]) from a
     * robot worker thread and reports how long each wait took — i.e. how long a click would wait.
     */
    fun edtLatency(robot: RemoteRobot, samples: Int = 40, intervalMillis: Int = 100): EdtLatency {
        val raw: String = robot.callJs(
            """
            var out = [];
            for (var i = 0; i < $samples; i++) {
                var t = java.lang.System.nanoTime();
                javax.swing.SwingUtilities.invokeAndWait(new java.lang.Runnable({ run: function() {} }));
                out.push((java.lang.System.nanoTime() - t) / 1000000.0);
                java.lang.Thread.sleep($intervalMillis);
            }
            out.join(",")
            """.trimIndent(),
            false,
        )
        val values = raw.split(',').map { it.toDouble() }.sorted()
        return EdtLatency(
            samples = values.size,
            maxMillis = values.last(),
            p95Millis = values[((values.size - 1) * 0.95).toInt()],
            meanMillis = values.average(),
        )
    }

    private fun readThreadTicks(pid: Long): Map<String, Pair<String, Long>> =
        File("/proc/$pid/task").listFiles().orEmpty().mapNotNull { task ->
            runCatching {
                val stat = File(task, "stat").readText()
                // comm is in parentheses and may contain spaces; fields after ") " are space separated.
                val name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'))
                val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                task.name to (name to fields[11].toLong() + fields[12].toLong()) // utime + stime
            }.getOrNull()
        }.toMap()

    private const val CLOCK_TICKS = 100.0
}
