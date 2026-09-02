package dev.acme.adbtoolbox.domain.adb

/**
 * One token of an [AdbShellCommand]. [Literal] is developer-authored command vocabulary (a
 * subcommand or flag baked into this project's own code, e.g. `"pm"`, `"list"`, `"-3"`) and is
 * rendered as-is. [Value] wraps caller/runtime data and is always rendered through
 * [ShellValue.quoted] — there is no [ShellToken] variant that renders runtime data unescaped.
 */
sealed interface ShellToken {
    @JvmInline
    value class Literal(val text: String) : ShellToken

    @JvmInline
    value class Value(val value: ShellValue) : ShellToken
}
