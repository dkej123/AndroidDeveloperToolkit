package io.github.dkej123.devicecockpit.domain.appdata

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * How the plugin can reach an app's private files. [RootShell]: adbd itself runs as root (`adb root`
 * on emulators and userdebug builds). [RunAs]: the app is debuggable, so `run-as` switches to its
 * uid on any device. [Su]: a rooted device with an `su` binary. Checked in that order.
 */
enum class AppDataAccess(val label: String) {
    RootShell("root shell"),
    RunAs("run-as"),
    Su("su"),
}

data class AppDataListing(val sharedPrefs: List<String>, val databases: List<String>)

private const val LISTING_SEPARATOR = "---"
private const val UPLOAD_TEMP = ".adbtoolbox-upload.tmp"
private val SQLITE_SIDE_FILE = Regex("""-(wal|shm|journal)$""")
private val NUMERIC_UID = Regex("""^\d+$""")
private val PACKAGE_NAME = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

/**
 * Command builders for reading and writing an app's `shared_prefs/` and `databases/` files. Every
 * app-side command is a single shell line run in the app's data directory through the chosen
 * [AppDataAccess]; file names inside it are quoted with [quote], and the whole line is quoted once
 * more as the `sh -c` argument, so names with spaces or quotes are safe at both levels.
 */
object AppDataCommands {

    fun shellUidRequest(serial: DeviceSerial): AdbDeviceRequest = shell(serial, literal("id"), literal("-u"))

    fun runAsProbeRequest(serial: DeviceSerial, packageName: String): AdbDeviceRequest =
        shell(serial, literal("run-as"), value(packageName), literal("id"), literal("-u"))

    fun suProbeRequest(serial: DeviceSerial): AdbDeviceRequest = shell(serial, literal("su"), literal("0"), literal("id"), literal("-u"))

    fun isRootUid(result: AdbTextResult): Boolean = result.outcome is AdbOutcome.Completed && result.stdout.trim() == "0"

    fun isNumericUid(result: AdbTextResult): Boolean =
        result.outcome is AdbOutcome.Completed && NUMERIC_UID.matches(result.stdout.trim())

    /** Runs [inner] (already-quoted shell text) with the app's data directory as the working directory. */
    fun inApp(serial: DeviceSerial, access: AppDataAccess, packageName: String, inner: String): AdbDeviceRequest {
        require(PACKAGE_NAME.matches(packageName)) { "not a package name: '$packageName'" }
        val inDataDir = "cd /data/data/$packageName && $inner"
        return when (access) {
            AppDataAccess.RootShell -> shell(serial, literal("sh"), literal("-c"), value(inDataDir))
            // run-as already starts in the app's data directory.
            AppDataAccess.RunAs -> shell(serial, literal("run-as"), value(packageName), literal("sh"), literal("-c"), value(inner))
            AppDataAccess.Su -> shell(serial, literal("su"), literal("0"), literal("sh"), literal("-c"), value(inDataDir))
        }
    }

    const val LIST_COMMAND = "ls -1 shared_prefs 2>/dev/null; echo $LISTING_SEPARATOR; ls -1 databases 2>/dev/null"

    fun parseListing(result: AdbTextResult): AppDataListing {
        val lines = result.stdout.lines().map { it.trim() }
        val separator = lines.indexOf(LISTING_SEPARATOR).takeIf { it >= 0 } ?: lines.size
        val prefs = lines.take(separator).filter { it.endsWith(".xml") }.sorted()
        val databases = lines.drop(separator + 1).filter { it.isNotEmpty() && !SQLITE_SIDE_FILE.containsMatchIn(it) }.sorted()
        return AppDataListing(prefs, databases)
    }

    fun catCommand(relativePath: String): String = "cat ${quote(relativePath)}"

    /**
     * The shell lines that write [content] to [relativePath] without stdin: base64 chunks appended to
     * a temp file in the data directory, then copied over the target with `cat >`, which keeps the
     * target file's owner and SELinux label (so the app can still write it afterwards, even when
     * the plugin wrote as root).
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun writeCommands(relativePath: String, content: ByteArray, chunkSize: Int = 48 * 1024): List<String> {
        val encoded = Base64.encode(content)
        val chunks = if (encoded.isEmpty()) listOf("") else encoded.chunked(chunkSize)
        return chunks.mapIndexed { index, chunk ->
            val redirect = if (index == 0) ">" else ">>"
            "printf '%s' ${quote(chunk)} | base64 -d $redirect ${quote(UPLOAD_TEMP)}"
        } + "cat ${quote(UPLOAD_TEMP)} > ${quote(relativePath)} && rm -f ${quote(UPLOAD_TEMP)}"
    }

    /** Single-quotes [text] for the device shell. */
    fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private fun literal(text: String) = ShellToken.Literal(text)

    private fun value(text: String) = ShellToken.Value(ShellValue.of(text))

    private fun shell(serial: DeviceSerial, vararg tokens: ShellToken) =
        AdbDeviceRequest(serial = serial, operation = AdbOperation.Shell(AdbShellCommand(tokens.toList())))
}
