package dev.acme.adbtoolbox.intellij.terminal

import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.domain.deviceactions.ShellSessionIntent
import dev.acme.adbtoolbox.domain.deviceactions.TerminalLaunchResult
import dev.acme.adbtoolbox.domain.deviceactions.TerminalLauncher
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * ADR 0007's Open-shell platform adapter (task 016): creates a new tab in the IDE's own integrated
 * Terminal tool window, pre-seeded with [ShellSessionIntent.commandLine] — never a bespoke
 * PTY/terminal UI (see ADR 0007's rejected alternatives). [terminalPluginPresent] is checked once
 * at composition ([dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService], the same
 * once-per-session guard already used for the optional Android plugin/ddmlib, ADR 0005) so this
 * class never touches an `org.jetbrains.plugins.terminal` class when that plugin is disabled — a
 * user can disable a bundled plugin even though it ships with every IntelliJ Platform install this
 * module targets. Per ADR 0007, an unavailable/failing Terminal plugin is a fixable
 * [TerminalLaunchResult.Unavailable], never a silent no-op or an uncaught exception.
 *
 * [dispatchers] is used (never `Dispatchers.EDT` directly, ADR 0004) to marshal the actual
 * `TerminalToolWindowManager`/`ShellTerminalWidget` calls onto the IDE's UI thread, matching this
 * plugin's one dispatcher-injection rule.
 */
class TerminalLauncherAdapter(
    private val project: Project,
    private val dispatchers: DispatcherProvider,
    private val terminalPluginPresent: Boolean,
) : TerminalLauncher {

    override suspend fun openShell(intent: ShellSessionIntent): TerminalLaunchResult {
        if (!terminalPluginPresent) {
            return TerminalLaunchResult.Unavailable("Open shell requires the Terminal plugin")
        }
        return withContext(dispatchers.main) {
            try {
                // createLocalShellWidget/ShellTerminalWidget.executeCommand are deprecated in favor
                // of createShellWidget's plain TerminalWidget (no typed executeCommand), but remain
                // functional through the 242 baseline this module targets (ADR 0003) and are the
                // only documented way to both create a tab AND pre-seed a command line into it —
                // accepted here the same way this codebase accepts other IntelliJ-Platform-version
                // risk (e.g. AdbTransportSelectionTest's class doc).
                @Suppress("DEPRECATION")
                val widget = TerminalToolWindowManager.getInstance(project)
                    .createLocalShellWidget(null, "adb shell (${intent.serial})")
                widget.executeCommand(intent.commandLine())
                TerminalLaunchResult.Launched
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                TerminalLaunchResult.Unavailable(error.message ?: "Failed to open shell")
            }
        }
    }
}
