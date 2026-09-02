package dev.acme.adbtoolbox.domain.discovery

/** The expected executable file name for [toolId] on [os] — `adb`/`scrcpy`, or with a `.exe`
 * extension on Windows. Pure and platform-neutral: the actual OS is always adapter-detected. */
fun executableFileName(toolId: ToolId, os: OperatingSystem): String {
    val base = when (toolId) {
        ToolId.Adb -> "adb"
        ToolId.Scrcpy -> "scrcpy"
    }
    return if (os == OperatingSystem.Windows) "$base.exe" else base
}

/** Joins a directory and a file name using [os]'s path separator, without relying on `java.nio.file`
 * or `java.io.File` (forbidden in `:domain` per ADR 0002). */
fun joinPath(directory: String, fileName: String, os: OperatingSystem): String {
    val separator = if (os == OperatingSystem.Windows) '\\' else '/'
    val trimmed = directory.trimEnd('/', '\\')
    return "$trimmed$separator$fileName"
}
