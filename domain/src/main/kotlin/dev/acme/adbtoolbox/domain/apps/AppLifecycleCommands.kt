package dev.acme.adbtoolbox.domain.apps

import dev.acme.adbtoolbox.domain.adb.AdbShellCommand
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.adb.ShellValue

/**
 * Builds the fixed `adb shell` command lines for task 023's Force-stop and Launch app-lifecycle
 * actions (`design/README.md` §4's Apps-view action row; exact command text pinned by
 * `design/IMPLEMENTATION.md` §4), from typed [ShellToken]s only (ADR 0005). [packageName] is
 * runtime/caller data, never developer-authored vocabulary, so it is always wrapped in
 * [ShellToken.Value]`(`[ShellValue.of]`)` — the single shared quoting mechanism — rather than
 * concatenated into a literal, matching
 * [dev.acme.adbtoolbox.domain.packages.PackageMetadataCommand]'s established shape for the same
 * `<pkg>` argument.
 *
 * [launch] uses the design-approved `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`
 * strategy (not a resolved-default-activity `am start`) — `design/IMPLEMENTATION.md` §4 pins this
 * exact command line, and it requires no separate "resolve the launcher activity" step/failure mode
 * beyond `monkey`'s own "no activities found" exit behavior (see
 * [dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase] for how that failure is surfaced).
 */
object AppLifecycleCommands {

    fun forceStop(packageName: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("am"),
        ShellToken.Literal("force-stop"),
        ShellToken.Value(ShellValue.of(packageName)),
    )

    fun launch(packageName: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("monkey"),
        ShellToken.Literal("-p"),
        ShellToken.Value(ShellValue.of(packageName)),
        ShellToken.Literal("-c"),
        ShellToken.Literal("android.intent.category.LAUNCHER"),
        ShellToken.Literal("1"),
    )
}
