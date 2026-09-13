package dev.acme.adbtoolbox.domain.adb

/**
 * What a device-scoped request asks the transport to do. [Shell] runs an [AdbShellCommand] through
 * the remote shell (`adb shell <line>`). [Host] runs a device-targeted host ADB subcommand
 * (`adb -s <serial> <args>`) such as uninstall. [Exec] runs `adb exec-out <args>` and is the
 * binary-output path (e.g. `screencap -p`). [Host] and [Exec] arguments are literal argv, never
 * shell-parsed, so no remote/local shell quoting applies.
 */
sealed interface AdbOperation {
    data class Shell(val command: AdbShellCommand) : AdbOperation

    data class Exec(val arguments: List<String>) : AdbOperation

    /** Device-scoped host ADB argv, such as `adb -s <serial> uninstall <package>`. */
    data class Host(val arguments: List<String>) : AdbOperation
}
