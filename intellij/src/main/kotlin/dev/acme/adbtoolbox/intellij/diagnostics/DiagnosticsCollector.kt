package dev.acme.adbtoolbox.intellij.diagnostics

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import dev.acme.adbtoolbox.adapters.jvm.diagnostics.JvmDiagnostics
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.time.Duration.Companion.seconds

/**
 * Assembles the "Collect Diagnostics…" ZIP: the plugin log files, an environment report, a live
 * adb report for [project], a thread dump and JVM stats, recent performance recordings, and the
 * IDE's own context (tail of idea.log and the newest freeze thread dumps). Blocking — call from a
 * background task, never the EDT.
 */
object DiagnosticsCollector {

    private const val IDEA_LOG_TAIL_BYTES = 2L * 1024 * 1024
    private const val RECENT_FREEZE_DUMPS = 3
    private const val RECENT_PERF_REPORTS = 3

    fun collect(project: Project?, target: Path = defaultTarget()): Path {
        val service = DiagnosticsService.getInstance()
        service.log.log(DiagLevel.INFO, DiagCategory.LIFECYCLE, "collecting diagnostics bundle", mapOf("target" to target))
        service.flush()
        val ideLogDir = PathManager.getLogDir()

        val entries = buildList {
            add(BundleEntry.Text("README.txt", readme()))
            add(BundleEntry.Lazy("environment.txt") { environmentReport(service) })
            if (project != null && !project.isDisposed) {
                add(BundleEntry.Lazy("adb.txt") { adbReport(project) })
            }
            add(BundleEntry.Lazy("threads.txt", JvmDiagnostics::threadDump))
            add(BundleEntry.Lazy("jvm.txt") { JvmDiagnostics.stats().entries.joinToString("\n") { "${it.key}: ${it.value}" } })
            service.logFiles().forEach { add(BundleEntry.FileCopy("adb-toolbox/${it.name}", it)) }
            newest(service.logDirectory, RECENT_PERF_REPORTS) { it.name.startsWith("perf-") }
                .forEach { add(BundleEntry.FileCopy("adb-toolbox/${it.name}", it)) }
            add(BundleEntry.FileTail("ide/idea.log", ideLogDir.resolve("idea.log"), IDEA_LOG_TAIL_BYTES))
            newest(ideLogDir, RECENT_FREEZE_DUMPS) { it.isDirectory() && it.name.startsWith("threadDumps-freeze") }
                .forEach { add(BundleEntry.Directory("ide/${it.name}", it)) }
        }
        return DiagnosticsBundleWriter.write(target, entries).also {
            service.log.log(DiagLevel.INFO, DiagCategory.LIFECYCLE, "diagnostics bundle written", mapOf("path" to it))
        }
    }

    /** `~/Desktop` when it exists (easy to find and attach), otherwise the plugin log folder. */
    fun defaultTarget(stamp: String = timestamp()): Path {
        val desktop = Path.of(System.getProperty("user.home"), "Desktop")
        val directory = if (desktop.isDirectory()) desktop else DiagnosticsService.getInstance().logDirectory
        return directory.resolve("adb-toolbox-diagnostics-$stamp.zip")
    }

    fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    private fun readme(): String = """
        ADB Toolbox diagnostics bundle
        environment.txt   plugin, IDE, OS, JDK, PATH/SDK variables
        adb.txt           live adb version/devices, tool discovery, ddmlib bridge, plugin device state
        threads.txt       thread dump taken while collecting
        jvm.txt           heap, GC and thread statistics
        adb-toolbox/      plugin diagnostics log (rotated) and performance recordings
        ide/              tail of idea.log and the IDE's newest freeze thread dumps
    """.trimIndent()

    private fun environmentReport(service: DiagnosticsService): String = buildString {
        DiagnosticsService.environmentFields().forEach { (key, value) -> append(key).append(": ").append(value).append('\n') }
        append("verboseDiagnostics: ").append(service.verbose).append('\n')
        append("logDirectory: ").append(service.logDirectory).append('\n')
        append('\n')
        for (name in listOf("PATH", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ADB", "JAVA_HOME")) {
            append(name).append(" (IDE process): ").append(System.getenv(name)).append('\n')
            append(name).append(" (login shell): ").append(EnvironmentUtil.getValue(name)).append('\n')
        }
        append('\n').append("jvmArguments: ")
            .append(java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")).append('\n')
    }

    // Bounded rather than progress-cancellable: the report must also work outside a progress task
    // (Settings button, tests), and every step inside it has its own timeout.
    private fun adbReport(project: Project): String = runBlocking {
        withTimeoutOrNull(60.seconds) { project.service<AdbToolboxProjectService>().adbDiagnosticsReport() }
            ?: "adb report timed out after 60 s"
    }

    private fun newest(directory: Path, limit: Int, filter: (Path) -> Boolean): List<Path> {
        if (!directory.isDirectory()) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter(filter).toList().sortedByDescending { Files.getLastModifiedTime(it) }.take(limit)
        }
    }
}
