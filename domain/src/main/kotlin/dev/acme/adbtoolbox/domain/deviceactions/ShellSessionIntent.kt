package dev.acme.adbtoolbox.domain.deviceactions

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * A platform-neutral request to open an interactive `adb -s $serial shell` session in the host
 * IDE's own terminal (task 016, ADR 0007) — carries only the exact argv a [TerminalLauncher]
 * adapter must pre-seed into a new terminal tab. Deliberately free of any IntelliJ/Swing/Terminal-
 * plugin type so this shared contract stays KMP-ready and reusable by a future standalone frontend
 * (ADR 0007: "task 016 launches the terminal command, it does not implement a shell session
 * itself").
 */
data class ShellSessionIntent(
    val serial: DeviceSerial,
    val adbExecutablePath: String,
) {
    /** The exact command line ADR 0007's platform terminal adapter pre-seeds into a new tab. */
    fun commandLine(): String = "$adbExecutablePath -s $serial shell"
}
