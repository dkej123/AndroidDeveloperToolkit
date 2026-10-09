package io.github.dkej123.devicecockpit.application.appdetails

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.appdata.AppDataAccess
import io.github.dkej123.devicecockpit.domain.appdata.AppDetails
import io.github.dkej123.devicecockpit.domain.appdata.PrefEntry
import io.github.dkej123.devicecockpit.domain.appdata.PrefType
import io.github.dkej123.devicecockpit.domain.appdata.SqlResult
import io.github.dkej123.devicecockpit.domain.appdata.SqlRows
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalysis
import io.github.dkej123.devicecockpit.domain.packages.AppIcon

/** Whether (and how) the app's private files can be read and written. */
sealed interface FileAccessState {
    data object Checking : FileAccessState

    data class Available(val access: AppDataAccess) : FileAccessState

    data class Unavailable(val reason: String) : FileAccessState
}

/** The SharedPreferences editor: one file's entries, edited locally until [dirty] is saved. */
data class PrefsEditorState(
    val fileName: String,
    val entries: List<PrefEntry> = emptyList(),
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

/** The database editor: a local copy of one database, pushed back to the device on save. */
data class DatabaseEditorState(
    val fileName: String,
    val tables: List<String> = emptyList(),
    val table: String? = null,
    val page: SqlRows? = null,
    val offset: Long = 0,
    val queryResult: SqlResult? = null,
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class AppDetailsState(
    val serial: DeviceSerial? = null,
    val packageName: String? = null,
    val label: String? = null,
    val icon: AppIcon? = null,
    val details: AppDetails? = null,
    val detailsError: String? = null,
    val androidUserId: Int = 0,
    val dangerousPermissions: Set<String> = emptySet(),
    val permissionBusy: String? = null,
    val deepLinks: DeepLinkAnalysis? = null,
    val deepLinksLoading: Boolean = false,
    val deepLinksError: String? = null,
    val runningPid: String? = null,
    val access: FileAccessState = FileAccessState.Checking,
    val sharedPrefsFiles: List<String> = emptyList(),
    val databaseFiles: List<String> = emptyList(),
    val prefs: PrefsEditorState? = null,
    val database: DatabaseEditorState? = null,
    /** A one-line result of the last save, e.g. "Saved settings.xml — app stopped". */
    val notice: String? = null,
) {
    val isOpen: Boolean get() = packageName != null
}

sealed interface AppDetailsIntent {
    data class Open(val packageName: String, val label: String, val icon: AppIcon?) : AppDetailsIntent
    data object Close : AppDetailsIntent
    data object Refresh : AppDetailsIntent
    data object AnalyzeDeepLinks : AppDetailsIntent
    data class OpenDeepLink(val uri: String) : AppDetailsIntent
    data class GrantPermission(val name: String) : AppDetailsIntent
    data class RevokePermission(val name: String) : AppDetailsIntent
    data class ResetPermission(val name: String) : AppDetailsIntent

    data class OpenPrefs(val fileName: String) : AppDetailsIntent
    /** Replaces the entry keyed [originalKey] (or adds one when `null`) — [valueInput] is parsed as [type]. */
    data class PutPref(val originalKey: String?, val key: String, val type: PrefType, val valueInput: String) : AppDetailsIntent
    data class RemovePref(val key: String) : AppDetailsIntent
    data object SavePrefs : AppDetailsIntent
    data object RevertPrefs : AppDetailsIntent

    data class OpenDatabase(val fileName: String) : AppDetailsIntent
    data class ShowTable(val table: String) : AppDetailsIntent
    data class ShowPage(val offset: Long) : AppDetailsIntent
    /** Sets one cell of the shown table page; `null` stores SQL NULL. */
    data class EditCell(val rowIndex: Int, val column: String, val value: String?) : AppDetailsIntent
    data class RunSql(val sql: String) : AppDetailsIntent
    data object SaveDatabase : AppDetailsIntent
    data object RevertDatabase : AppDetailsIntent
}
