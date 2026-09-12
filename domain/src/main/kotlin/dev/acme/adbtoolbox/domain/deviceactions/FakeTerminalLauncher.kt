package dev.acme.adbtoolbox.domain.deviceactions

/**
 * A deterministic [TerminalLauncher] test double: returns a caller-supplied [TerminalLaunchResult]
 * instead of touching a real IntelliJ Terminal tool window, and records every [openShell] call so
 * tests can assert the exact [ShellSessionIntent] a use case built.
 */
class FakeTerminalLauncher(
    private val result: (ShellSessionIntent) -> TerminalLaunchResult = { TerminalLaunchResult.Launched },
) : TerminalLauncher {

    private val _openShellCalls = mutableListOf<ShellSessionIntent>()
    val openShellCalls: List<ShellSessionIntent> get() = _openShellCalls

    override suspend fun openShell(intent: ShellSessionIntent): TerminalLaunchResult {
        _openShellCalls += intent
        return result(intent)
    }
}
