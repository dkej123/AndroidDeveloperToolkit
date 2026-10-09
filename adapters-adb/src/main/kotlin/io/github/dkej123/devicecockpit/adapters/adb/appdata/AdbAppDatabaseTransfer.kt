package io.github.dkej123.devicecockpit.adapters.adb.appdata

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.appdata.AppDataAccess
import io.github.dkej123.devicecockpit.domain.appdata.AppDataCommands
import io.github.dkej123.devicecockpit.domain.appdata.AppDatabaseTransfer
import io.github.dkej123.devicecockpit.domain.appdata.DatabaseCopy
import java.io.File
import java.nio.file.Files
import java.util.UUID

private const val SQLITE_MAGIC = "SQLite format 3"

/**
 * [AppDatabaseTransfer] over [AdbTransport]. Pulls stream `cat` output through the binary path
 * (`exec-out`), never a text shell, so database bytes are not decoded. Pushes go through
 * `/data/local/tmp`: `adb push` there (a shell-owned directory every app can traverse), then the
 * app-side command copies it over the database with `cat >` so the file keeps its owner and label.
 */
class AdbAppDatabaseTransfer(
    private val transport: AdbTransport,
    private val tempRoot: () -> File = { Files.createTempDirectory("adb-toolbox-db-").toFile().apply { deleteOnExit() } },
) : AppDatabaseTransfer {

    override suspend fun pull(serial: DeviceSerial, access: AppDataAccess, packageName: String, fileName: String): DatabaseCopy {
        val directory = tempRoot()
        val main = File(directory, fileName).apply { deleteOnExit() }
        val mainError = pullFile(serial, access, packageName, "databases/$fileName", main)
        if (mainError != null) return DatabaseCopy.Failed(mainError)
        val walListing = transport.executeText(
            AppDataCommands.inApp(serial, access, packageName, "ls ${AppDataCommands.quote("databases/$fileName-wal")} 2>/dev/null"),
        )
        if (walListing.stdout.isNotBlank()) {
            // Without its WAL the copy could miss recent commits; with it SQLite replays them on open.
            pullFile(serial, access, packageName, "databases/$fileName-wal", File(directory, "$fileName-wal").apply { deleteOnExit() })
                ?.let { return DatabaseCopy.Failed(it) }
        }
        return DatabaseCopy.Pulled(main.absolutePath)
    }

    override suspend fun push(
        serial: DeviceSerial,
        access: AppDataAccess,
        packageName: String,
        fileName: String,
        localPath: String,
    ): String? {
        val local = File(localPath)
        // adb push keeps the file mode; the app's uid must be able to read the staged copy.
        local.setReadable(true, false)
        val staged = "/data/local/tmp/adb-toolbox-${UUID.randomUUID()}.db"
        val pushed = transport.executeText(AdbDeviceRequest(serial, AdbOperation.Host(listOf("push", local.absolutePath, staged))))
        if (!pushed.succeeded()) return "adb push failed: ${pushed.stderr.trim().ifEmpty { pushed.outcome.toString() }}"
        try {
            val target = "databases/$fileName"
            val q = AppDataCommands::quote
            val copy = "cat ${q(staged)} > ${q(target)} && rm -f ${q("$target-wal")} ${q("$target-shm")} ${q("$target-journal")} && echo done"
            val result = transport.executeText(AppDataCommands.inApp(serial, access, packageName, copy))
            if (!result.succeeded() || !result.stdout.contains("done")) {
                return "Could not replace $target: ${(result.stdout + result.stderr).trim().ifEmpty { result.outcome.toString() }}"
            }
            return null
        } finally {
            transport.executeText(
                AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(ShellToken.Literal("rm"), ShellToken.Literal("-f"), ShellToken.Literal(q(staged))))),
            )
        }
    }

    private suspend fun pullFile(serial: DeviceSerial, access: AppDataAccess, packageName: String, relativePath: String, into: File): String? {
        val q = AppDataCommands::quote
        val arguments = when (access) {
            AppDataAccess.RootShell -> listOf("cat", q("/data/data/$packageName/$relativePath"))
            AppDataAccess.RunAs -> listOf("run-as", q(packageName), "cat", q(relativePath))
            AppDataAccess.Su -> listOf("su", "0", "cat", q("/data/data/$packageName/$relativePath"))
        }
        val outcome = into.outputStream().buffered().use { out ->
            transport.executeBinary(AdbDeviceRequest(serial, AdbOperation.Exec(arguments))) { bytes -> out.write(bytes) }
        }
        if (outcome !is AdbOutcome.Completed || (outcome.exitCode != null && outcome.exitCode != 0)) return "Could not read $relativePath: $outcome"
        // `cat` errors arrive on the same stream as the bytes; a real SQLite file starts with its magic header.
        if (!relativePath.endsWith("-wal") && !hasSqliteHeader(into)) {
            return "Could not read $relativePath: ${into.inputStream().use { String(it.readNBytes(200)) }.trim()}"
        }
        return null
    }

    private fun q(text: String) = AppDataCommands.quote(text)

    private fun hasSqliteHeader(file: File): Boolean =
        file.inputStream().use { String(it.readNBytes(SQLITE_MAGIC.length), Charsets.US_ASCII) } == SQLITE_MAGIC

    private fun io.github.dkej123.devicecockpit.domain.adb.AdbTextResult.succeeded(): Boolean {
        val result = outcome
        return result is AdbOutcome.Completed && (result.exitCode == null || result.exitCode == 0)
    }
}
