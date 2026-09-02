package dev.acme.adbtoolbox.domain.adb

/**
 * A remote shell command line built only from typed [ShellToken]s — the single shared quoting
 * mechanism every feature-local command factory uses (per ADR 0005), rather than each factory
 * string-concatenating its own shell command. [render] is the only way to obtain the shell text;
 * there is no constructor that accepts a raw, pre-assembled command string.
 */
data class AdbShellCommand(val tokens: List<ShellToken>) {

    init {
        require(tokens.isNotEmpty()) { "AdbShellCommand must have at least one token" }
    }

    fun render(): String = tokens.joinToString(" ") { token ->
        when (token) {
            is ShellToken.Literal -> token.text
            is ShellToken.Value -> token.value.quoted()
        }
    }

    companion object {
        fun of(vararg tokens: ShellToken): AdbShellCommand = AdbShellCommand(tokens.toList())
    }
}
