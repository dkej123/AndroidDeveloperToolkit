package dev.acme.adbtoolbox.domain.adb

/**
 * What a device-scoped request asks the transport to do. [Shell] runs an [AdbShellCommand] through
 * the remote shell (`adb shell <line>`) and is text-shaped. [Exec] runs a fixed argument vector
 * without a remote shell (`adb exec-out <args>`) and is the binary-output path (e.g.
 * `screencap -p`); its arguments are a literal argv, never shell-parsed, so no quoting applies —
 * the same "never concatenate a local shell" guarantee ADR 0001 already gives the local process
 * boundary.
 */
sealed interface AdbOperation {
    data class Shell(val command: AdbShellCommand) : AdbOperation

    data class Exec(val arguments: List<String>) : AdbOperation
}
