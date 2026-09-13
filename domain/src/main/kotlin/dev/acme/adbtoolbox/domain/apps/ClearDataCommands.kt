package dev.acme.adbtoolbox.domain.apps

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue

/**
 * Builds the fixed `adb shell` command line for task 024's destructive Clear-data action
 * (`design/README.md` §4's Apps-view Destructive group; exact command text pinned by
 * `design/IMPLEMENTATION.md` §4: `pm clear <pkg>`), from typed [ShellToken]s only (ADR 0005).
 * [packageName] is runtime/caller data, never developer-authored vocabulary, so it is always
 * wrapped in [ShellToken.Value]`(`[ShellValue.of]`)` — the single shared quoting mechanism —
 * rather than concatenated into a literal, matching
 * [dev.acme.adbtoolbox.domain.apps.AppLifecycleCommands]'s established shape for the same
 * `<pkg>` argument.
 */
object ClearDataCommands {

    fun clear(packageName: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("pm"),
        ShellToken.Literal("clear"),
        ShellToken.Value(ShellValue.of(packageName)),
    )
}
