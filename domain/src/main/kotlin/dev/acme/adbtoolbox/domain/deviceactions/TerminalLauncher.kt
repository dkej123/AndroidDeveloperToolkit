package dev.acme.adbtoolbox.domain.deviceactions

/**
 * The platform-neutral Open-shell port (task 016, ADR 0007): implemented by exactly one
 * `:intellij` adapter that creates a new tab in the IDE's own integrated Terminal tool window,
 * pre-seeded with [ShellSessionIntent.commandLine]. This interface exposes no IntelliJ/Swing/
 * Terminal-plugin type (task 016's acceptance criteria: "shared contracts expose no IntelliJ
 * terminal type") — [TerminalLaunchResult] is the only thing a caller ever sees back.
 */
interface TerminalLauncher {
    suspend fun openShell(intent: ShellSessionIntent): TerminalLaunchResult
}

/**
 * What a [TerminalLauncher.openShell] call produced. [Unavailable] covers every reason a shell
 * could not be opened (the Terminal plugin missing/disabled, or the platform adapter itself
 * failing) with a fixable, user-facing [reason] — per ADR 0007, "never a silent no-op."
 */
sealed interface TerminalLaunchResult {
    data object Launched : TerminalLaunchResult
    data class Unavailable(val reason: String) : TerminalLaunchResult
}
