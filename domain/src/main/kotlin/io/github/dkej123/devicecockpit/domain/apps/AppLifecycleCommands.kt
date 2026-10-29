package io.github.dkej123.devicecockpit.domain.apps

import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue

/**
 * Builds the fixed `adb shell` command lines for task 023's Force-stop and Launch app-lifecycle
 * actions (`design/README.md` §4's Apps-view action row; exact command text pinned by
 * `design/IMPLEMENTATION.md` §4), from typed [ShellToken]s only (ADR 0005). [packageName] is
 * runtime/caller data, never developer-authored vocabulary, so it is always wrapped in
 * [ShellToken.Value]`(`[ShellValue.of]`)` — the single shared quoting mechanism — rather than
 * concatenated into a literal, matching
 * [io.github.dkej123.devicecockpit.domain.packages.PackageMetadataCommand]'s established shape for the same
 * `<pkg>` argument.
 *
 * [launch] starts the first LAUNCHER activity and falls back to `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`
 * (`design/IMPLEMENTATION.md` §4), keeping `monkey`'s own "no activities found" failure (see
 * [io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase] for how that failure is surfaced).
 */
object AppLifecycleCommands {

    fun forceStop(packageName: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("am"),
        ShellToken.Literal("force-stop"),
        ShellToken.Value(ShellValue.of(packageName)),
    )

    /** Sends the app in front to the background, as the user pressing Home does. */
    fun home(): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("input"),
        ShellToken.Literal("keyevent"),
        ShellToken.Literal("KEYCODE_HOME"),
    )

    /**
     * Kills [packageName]'s process only if it is in the background — what the system does to a cached
     * app under memory pressure — keeping its tasks and saved instance state, unlike [forceStop].
     */
    fun killBackground(packageName: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("am"),
        ShellToken.Literal("kill"),
        ShellToken.Value(ShellValue.of(packageName)),
    )

    /**
     * Starts the first LAUNCHER activity in manifest order — what Android's `getLaunchIntentForPackage` picks — with the
     * launcher's own flags. `monkey` alone picks a random LAUNCHER activity, so an app that also declares LeakCanary's or a
     * dev-tools launcher opened those at random; it stays the fallback when the query finds nothing, keeping its
     * "No activities found" failure. One device shell line, so callers still see a single launch command.
     */
    fun launch(packageName: String): AdbShellCommand = AdbShellCommand.of(
        ShellToken.Literal("c=\$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"),
        ShellToken.Value(ShellValue.of(packageName)),
        ShellToken.Literal("| grep -m1 / | tr -d ' ') && [ -n \"\$c\" ]"),
        ShellToken.Literal("&& am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n \"\$c\""),
        ShellToken.Literal("|| monkey -p"),
        ShellToken.Value(ShellValue.of(packageName)),
        ShellToken.Literal("-c"),
        ShellToken.Literal("android.intent.category.LAUNCHER"),
        ShellToken.Literal("1"),
    )
}
