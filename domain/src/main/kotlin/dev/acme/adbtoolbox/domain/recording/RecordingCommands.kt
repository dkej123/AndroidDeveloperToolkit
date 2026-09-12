package dev.acme.adbtoolbox.domain.recording

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue

private const val REMOTE_RECORDING_DIRECTORY = "/sdcard"

/**
 * Builds task 020's remote recording command lines/argv (`design/IMPLEMENTATION.md` §4), from typed
 * [ShellToken]s only (ADR 0005) — no feature-local string concatenation of its own.
 */
object RecordingCommands {

    /** Where [baseFileName] (a [dev.acme.adbtoolbox.domain.capture.FileNamePolicy] result) is
     * recorded to on the device before being pulled and cleaned up. */
    fun remotePath(baseFileName: String): String = "$REMOTE_RECORDING_DIRECTORY/$baseFileName"

    /** `adb -s $S shell screenrecord <remotePath>` — `design/IMPLEMENTATION.md` §4's "record start". */
    fun startRecording(remotePath: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("screenrecord"),
        ShellToken.Value(ShellValue.of(remotePath)),
    )

    /**
     * The literal `exec-out` argv used to retrieve [remotePath]'s bytes
     * ([dev.acme.adbtoolbox.domain.adb.AdbOperation.Exec] — never shell-parsed): `cat` over
     * `exec-out` streams the file byte-for-byte through the same binary-safe channel task 019's
     * `screencap -p` capture already uses, without needing a separate sync-protocol pull port.
     */
    fun pull(remotePath: String): List<String> = listOf("cat", remotePath)

    /** `adb -s $S shell rm -f <remotePath>` — `design/IMPLEMENTATION.md` §4's "record stop" remote
     * cleanup, run only once the pulled copy has been committed locally. */
    fun cleanup(remotePath: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("rm"),
        ShellToken.Literal("-f"),
        ShellToken.Value(ShellValue.of(remotePath)),
    )
}
