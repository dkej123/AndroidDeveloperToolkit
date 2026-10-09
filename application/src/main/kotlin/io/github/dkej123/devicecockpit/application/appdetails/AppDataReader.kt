package io.github.dkej123.devicecockpit.application.appdetails

import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.appdata.AppDataAccess
import io.github.dkej123.devicecockpit.domain.appdata.AppDataCommands
import io.github.dkej123.devicecockpit.domain.appdata.AppDataListing
import io.github.dkej123.devicecockpit.domain.appdata.AppDatabaseTransfer
import io.github.dkej123.devicecockpit.domain.appdata.DatabaseCopy
import io.github.dkej123.devicecockpit.domain.appdata.PrefEntry
import io.github.dkej123.devicecockpit.domain.appdata.SharedPrefsParse
import io.github.dkej123.devicecockpit.domain.appdata.SharedPrefsXml
import io.github.dkej123.devicecockpit.domain.appdata.SqlResult
import io.github.dkej123.devicecockpit.domain.appdata.SqliteEngine

sealed interface AppDataRead<out T> {
    data class Read<T>(val value: T) : AppDataRead<T>

    data class Failed(val reason: String) : AppDataRead<Nothing>
}

/**
 * Read-only access to an app's shared preferences and databases (ADR 0011) for the MCP data tools:
 * the same access probe as App details (root shell, run-as, su), files read with `cat`, databases
 * pulled to a host copy and queried there, so nothing on the device changes.
 */
class AppDataReader(
    private val transport: AdbTransport,
    private val databases: AppDatabaseTransfer,
    private val sqlite: SqliteEngine,
) {
    suspend fun access(serial: DeviceSerial, packageName: String): AppDataAccess? = when {
        AppDataCommands.isRootUid(transport.executeText(AppDataCommands.shellUidRequest(serial))) -> AppDataAccess.RootShell
        AppDataCommands.isNumericUid(transport.executeText(AppDataCommands.runAsProbeRequest(serial, packageName))) -> AppDataAccess.RunAs
        AppDataCommands.isRootUid(transport.executeText(AppDataCommands.suProbeRequest(serial))) -> AppDataAccess.Su
        else -> null
    }

    suspend fun listing(serial: DeviceSerial, packageName: String): AppDataRead<Pair<AppDataAccess, AppDataListing>> {
        val access = access(serial, packageName) ?: return noAccess(packageName)
        val listing = AppDataCommands.parseListing(transport.executeText(AppDataCommands.inApp(serial, access, packageName, AppDataCommands.LIST_COMMAND)))
        return AppDataRead.Read(access to listing)
    }

    suspend fun preferences(serial: DeviceSerial, packageName: String, access: AppDataAccess, fileName: String): AppDataRead<List<PrefEntry>> {
        val read = transport.executeText(AppDataCommands.inApp(serial, access, packageName, AppDataCommands.catCommand("shared_prefs/$fileName")))
        if (read.outcome !is AdbOutcome.Completed) return AppDataRead.Failed("Couldn't read shared_prefs/$fileName")
        return when (val parsed = SharedPrefsXml.parse(read.stdout)) {
            is SharedPrefsParse.Parsed -> AppDataRead.Read(parsed.entries)
            is SharedPrefsParse.Malformed -> AppDataRead.Failed("shared_prefs/$fileName is not a preferences file: ${parsed.reason}")
        }
    }

    /** Runs [sql] (a query) on a fresh host copy of the database; the copy is discarded afterwards. */
    suspend fun query(serial: DeviceSerial, packageName: String, access: AppDataAccess, fileName: String, sql: String, maxRows: Int): AppDataRead<SqlResult> {
        val copy = databases.pull(serial, access, packageName, fileName)
        if (copy is DatabaseCopy.Failed) return AppDataRead.Failed(copy.reason)
        val session = sqlite.open((copy as DatabaseCopy.Pulled).localPath)
        return try {
            AppDataRead.Read(session.execute(sql, maxRows))
        } finally {
            session.close()
        }
    }

    private fun noAccess(packageName: String) = AppDataRead.Failed(
        "Can't read the private files of $packageName: it is not debuggable (run-as) and the device is not rooted.",
    )
}
