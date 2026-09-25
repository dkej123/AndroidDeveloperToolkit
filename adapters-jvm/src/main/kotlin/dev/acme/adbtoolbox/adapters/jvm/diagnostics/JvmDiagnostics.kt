package dev.acme.adbtoolbox.adapters.jvm.diagnostics

import java.lang.management.ManagementFactory

/** Point-in-time JVM facts for the periodic stats line and the diagnostics bundle. */
object JvmDiagnostics {

    fun stats(): Map<String, Any?> {
        val memory = ManagementFactory.getMemoryMXBean()
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        val threads = ManagementFactory.getThreadMXBean()
        return linkedMapOf(
            "heapUsedMb" to heap.used / MB,
            "heapCommittedMb" to heap.committed / MB,
            "heapMaxMb" to heap.max.takeIf { it > 0 }?.div(MB),
            "nonHeapUsedMb" to nonHeap.used / MB,
            "gcCount" to gcs.sumOf { it.collectionCount.coerceAtLeast(0) },
            "gcTimeMs" to gcs.sumOf { it.collectionTime.coerceAtLeast(0) },
            "threads" to threads.threadCount,
            "peakThreads" to threads.peakThreadCount,
            "cpus" to Runtime.getRuntime().availableProcessors(),
            "processCpuLoad" to processCpuLoad(),
            "uptimeMin" to ManagementFactory.getRuntimeMXBean().uptime / 60_000,
        )
    }

    /** A full thread dump (all threads, locks, full stacks) in `jstack`-like text. */
    fun threadDump(): String = buildString {
        val threads = ManagementFactory.getThreadMXBean()
        val infos = threads.dumpAllThreads(threads.isObjectMonitorUsageSupported, threads.isSynchronizerUsageSupported)
        infos.sortedBy { it.threadName }.forEach { info ->
            append('"').append(info.threadName).append("\" id=").append(info.threadId)
                .append(" state=").append(info.threadState)
            info.lockName?.let { append(" waitingOn=").append(it) }
            info.lockOwnerName?.let { append(" heldBy=\"").append(it).append('"') }
            append('\n')
            info.stackTrace.forEach { append("    at ").append(it).append('\n') }
            info.lockedMonitors.forEach { append("    - locked ").append(it).append('\n') }
            append('\n')
        }
        threads.findDeadlockedThreads()?.let { ids -> append("DEADLOCKED thread ids: ").append(ids.joinToString()).append('\n') }
    }

    private fun processCpuLoad(): String? {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val load = (os as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad ?: return null
        return if (load < 0) null else "%.0f%%".format(load * 100)
    }

    private const val MB = 1024L * 1024L
}
