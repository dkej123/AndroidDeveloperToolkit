package dev.acme.adbtoolbox.domain.adb

/**
 * A runtime-supplied value destined for the Android remote shell (a package name, path, or other
 * caller data) — never a hardcoded command keyword. [ShellValue] carries no escaping of its own;
 * it is escaped exactly once, at render time, by [AdbShellCommand.render] via [quoted]. There is
 * no way to turn a [ShellValue] into shell text other than through that path, so a value can never
 * be interpolated unescaped into a command line.
 */
@JvmInline
value class ShellValue private constructor(val raw: String) {

    companion object {
        fun of(raw: String): ShellValue = ShellValue(raw)
    }
}

/**
 * Single-quote wrapping with embedded-quote escaping for the Android (`sh`-compatible) shell: each
 * embedded `'` becomes `'\''` — closing the quoted string, escaping a literal quote, then reopening
 * quoting. The result is always safe to place between two spaces in a shell command line.
 */
internal fun ShellValue.quoted(): String = "'" + raw.replace("'", "'\\''") + "'"
