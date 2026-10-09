package io.github.dkej123.devicecockpit.intellij.terminal

import com.intellij.openapi.project.Project
import io.github.dkej123.devicecockpit.domain.deviceactions.ShellSessionIntent
import io.github.dkej123.devicecockpit.domain.deviceactions.TerminalLaunchResult
import io.github.dkej123.devicecockpit.domain.deviceactions.TerminalLauncher
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * ADR 0007's Open-shell platform adapter (task 016): creates a new tab in the IDE's own integrated
 * Terminal tool window, pre-seeded with [ShellSessionIntent.commandLine] — never a bespoke
 * PTY/terminal UI (see ADR 0007's rejected alternatives). [terminalPluginPresent] is checked once
 * at composition ([io.github.dkej123.devicecockpit.intellij.composition.AdbToolboxProjectService], the same
 * once-per-session guard already used for the optional Android plugin/ddmlib, ADR 0005) so this
 * class never touches an `org.jetbrains.plugins.terminal` class when that plugin is disabled — a
 * user can disable a bundled plugin even though it ships with every IntelliJ Platform install this
 * module targets. Per ADR 0007, an unavailable/failing Terminal plugin is a fixable
 * [TerminalLaunchResult.Unavailable], never a silent no-op or an uncaught exception.
 *
 * [dispatchers] is used (never `Dispatchers.EDT` directly, ADR 0004) to marshal the actual
 * `TerminalToolWindowManager`/`TerminalWidget` calls onto the IDE's UI thread, matching this
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
                val widget = createShellTab(TerminalToolWindowManager.getInstance(project), "adb shell (${intent.serial})")
                widget.sendCommandToExecute(intent.commandLine())
                TerminalLaunchResult.Launched
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                TerminalLaunchResult.Unavailable(error.message ?: "Failed to open shell")
            }
        }
    }

    /**
     * `createShellWidget(workingDirectory, tabName, requestFocus, deferSessionStartUntilUiShown)`,
     * called reflectively: it is the only tab-creating call present across the whole supported
     * range (242+), yet 2026.3 deprecates it for the Reworked Terminal API, which 242 lacks — so any
     * direct call is flagged by the Marketplace verifier on one end of the range. If a future IDE
     * removes it, the caller reports [TerminalLaunchResult.Unavailable] instead of crashing.
     */
    internal fun createShellTab(manager: TerminalToolWindowManager, tabName: String): TerminalWidget {
        val method = manager.javaClass.getMethod(
            "createShellWidget",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        return try {
            method.invoke(manager, null, tabName, true, true) as TerminalWidget
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            throw wrapped.targetException
        }
    }
}
