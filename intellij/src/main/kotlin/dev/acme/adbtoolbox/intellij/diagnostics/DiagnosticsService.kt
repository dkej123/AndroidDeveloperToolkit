package dev.acme.adbtoolbox.intellij.diagnostics

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.concurrency.AppExecutorUtil
import dev.acme.adbtoolbox.adapters.jvm.diagnostics.FileDiagnosticsLog
import dev.acme.adbtoolbox.adapters.jvm.diagnostics.JvmDiagnostics
import dev.acme.adbtoolbox.application.diagnostics.CommandStatsCollector
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the plugin's single diagnostics log (`<IDE log dir>/adb-toolbox/adb-toolbox.log`), shared
 * by every open project, plus the command-timing stats and the periodic JVM/stats line. WARN and
 * ERROR entries are mirrored to idea.log. The verbose (DEBUG) toggle is an application-level
 * preference, since the log itself is application-wide.
 */
@Service(Service.Level.APP)
@State(name = "AdbToolboxDiagnostics", storages = [Storage("adbToolboxDiagnostics.xml")])
class DiagnosticsService : PersistentStateComponent<DiagnosticsService.Settings>, Disposable {

    class Settings {
        var verbose: Boolean = false
    }

    private var settings = Settings()

    val logDirectory: Path = PathManager.getLogDir().resolve("adb-toolbox")

    private val fileLog = FileDiagnosticsLog(
        directory = logDirectory,
        minLevel = DiagLevel.INFO,
        mirror = { _, line, error -> IDE_LOG.warn(line, error) },
    )

    val log: DiagnosticsLog get() = fileLog

    val commandStats = CommandStatsCollector()

    private val ticker: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(::logPeriodicStats, STATS_PERIOD_SECONDS, STATS_PERIOD_SECONDS, TimeUnit.SECONDS)

    init {
        fileLog.log(DiagLevel.INFO, DiagCategory.LIFECYCLE, "diagnostics started", environmentFields())
    }

    var verbose: Boolean
        get() = settings.verbose
        set(value) {
            settings.verbose = value
            applyLevel()
            fileLog.log(DiagLevel.INFO, DiagCategory.LIFECYCLE, "verbose diagnostics changed", mapOf("verbose" to value))
        }

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
        applyLevel()
    }

    /** Everything logged so far is on disk once this returns (bounded wait). */
    fun flush() = fileLog.flush()

    fun logFiles(): List<Path> = fileLog.files()

    private fun applyLevel() {
        fileLog.minLevel = if (settings.verbose) DiagLevel.DEBUG else DiagLevel.INFO
    }

    private fun logPeriodicStats() {
        runCatching {
            fileLog.log(DiagLevel.INFO, DiagCategory.PERF, "jvm", JvmDiagnostics.stats())
            val commands = commandStats.drain()
            if (commands.isNotEmpty()) {
                fileLog.log(
                    DiagLevel.INFO,
                    DiagCategory.PERF,
                    "commands in last ${STATS_PERIOD_SECONDS}s",
                    mapOf(
                        "total" to commands.sumOf { it.count },
                        "top" to commands.take(8).joinToString("; ") {
                            "${it.key} n=${it.count} avg=${it.averageMs}ms max=${it.maxMs}ms fail=${it.failures}"
                        },
                    ),
                )
            }
        }
    }

    override fun dispose() {
        ticker.cancel(false)
        fileLog.log(DiagLevel.INFO, DiagCategory.LIFECYCLE, "diagnostics stopped")
        fileLog.close()
    }

    companion object {
        private val IDE_LOG = Logger.getInstance("#dev.acme.adbtoolbox")
        private const val STATS_PERIOD_SECONDS = 60L
        const val PLUGIN_ID = "dev.acme.adbtoolbox"

        fun getInstance(): DiagnosticsService = service()

        /** Plugin, IDE, OS and JDK facts — the lifecycle header and the bundle's environment section. */
        fun environmentFields(): Map<String, Any?> {
            val app = ApplicationInfo.getInstance()
            fun plugin(id: String) = PluginManagerCore.getPlugin(PluginId.getId(id))
            return linkedMapOf(
                "plugin" to plugin(PLUGIN_ID)?.version,
                "ide" to app.fullApplicationName,
                "build" to app.build.asString(),
                "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
                "jdk" to "${System.getProperty("java.version")} ${System.getProperty("java.vendor")}",
                "androidPlugin" to plugin("org.jetbrains.android")?.let { "${it.version} enabled=${it.isEnabled}" },
                "terminalPlugin" to plugin("org.jetbrains.plugins.terminal")?.let { "enabled=${it.isEnabled}" },
            )
        }
    }
}
