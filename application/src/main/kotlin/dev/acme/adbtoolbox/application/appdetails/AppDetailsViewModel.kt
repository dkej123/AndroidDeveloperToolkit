package dev.acme.adbtoolbox.application.appdetails

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.appdata.AppDataAccess
import dev.acme.adbtoolbox.domain.appdata.AppDataCommands
import dev.acme.adbtoolbox.domain.appdata.AppDatabaseTransfer
import dev.acme.adbtoolbox.domain.appdata.AppDetailsParser
import dev.acme.adbtoolbox.domain.appdata.DatabaseCopy
import dev.acme.adbtoolbox.domain.appdata.PrefEntry
import dev.acme.adbtoolbox.domain.appdata.PrefValue
import dev.acme.adbtoolbox.domain.appdata.SharedPrefsParse
import dev.acme.adbtoolbox.domain.appdata.SharedPrefsXml
import dev.acme.adbtoolbox.domain.appdata.SqlResult
import dev.acme.adbtoolbox.domain.appdata.SqliteEngine
import dev.acme.adbtoolbox.domain.appdata.SqliteSession
import dev.acme.adbtoolbox.domain.apps.AppLifecycleCommands
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.selectedSerialOrNull
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.LogcatPidCommand
import dev.acme.adbtoolbox.domain.packages.PackageMetadataCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PAGE_SIZE = 100
private const val MAX_QUERY_ROWS = 500

private const val NO_ACCESS =
    "This app's files are private. Android lets other tools read them only through run-as, which needs " +
        "a debuggable build of the app, or on a rooted device / an emulator without Google Play."

/**
 * The Apps view's detail page for one package: everything `dumpsys package` reports, the running
 * process, and editors for its `shared_prefs/` files and `databases/`.
 *
 * File access is probed once per opened app ([FileAccessState]): a root adb shell, then `run-as`
 * (debuggable apps), then `su`. Edits stay local until saved; saving first force-stops the app so
 * its in-memory copy cannot overwrite the change, then writes the file back — prefs as XML through
 * [AppDataCommands.writeCommands], databases by pushing the checkpointed local copy
 * ([AppDatabaseTransfer]). Every operation runs one at a time, and results for an app that has
 * since been closed or replaced are dropped.
 */
class AppDetailsViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    private val databases: AppDatabaseTransfer,
    private val sqlite: SqliteEngine,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
) {
    private val _state = MutableStateFlow(AppDetailsState())
    val state: StateFlow<AppDetailsState> = _state.asStateFlow()

    private val currentSerial = selectedDeviceState
    private val work = Mutex()
    private var generation = 0L
    private var session: SqliteSession? = null
    private var sessionPath: String? = null
    private var loadedPrefs: List<PrefEntry> = emptyList()

    init {
        scope.launch(dispatchers.default) {
            // Another device: the open app (and any unsaved edit) belonged to the previous one.
            selectedDeviceState.map { it.selectedSerialOrNull }.distinctUntilChanged().drop(1).collect { handle(AppDetailsIntent.Close) }
        }
    }

    fun handle(intent: AppDetailsIntent) {
        when (intent) {
            is AppDetailsIntent.Open -> open(intent)
            AppDetailsIntent.Close -> {
                generation++
                _state.value = AppDetailsState()
                enqueue { closeSession() }
            }
            AppDetailsIntent.Refresh -> _state.value.packageName?.let { open(AppDetailsIntent.Open(it, _state.value.label ?: it, _state.value.icon)) }
            is AppDetailsIntent.OpenPrefs -> enqueue { openPrefs(intent.fileName) }
            is AppDetailsIntent.PutPref -> putPref(intent)
            is AppDetailsIntent.RemovePref -> updatePrefs { entries -> entries.filterNot { it.key == intent.key } }
            AppDetailsIntent.SavePrefs -> enqueue { savePrefs() }
            AppDetailsIntent.RevertPrefs -> _state.update { s -> s.copy(prefs = s.prefs?.copy(entries = loadedPrefs, dirty = false, error = null)) }
            is AppDetailsIntent.OpenDatabase -> enqueue { openDatabase(intent.fileName) }
            is AppDetailsIntent.ShowTable -> enqueue { showPage(intent.table, 0) }
            is AppDetailsIntent.ShowPage -> enqueue { _state.value.database?.table?.let { showPage(it, intent.offset.coerceAtLeast(0)) } }
            is AppDetailsIntent.EditCell -> enqueue { editCell(intent) }
            is AppDetailsIntent.RunSql -> enqueue { runSql(intent.sql) }
            AppDetailsIntent.SaveDatabase -> enqueue { saveDatabase() }
            AppDetailsIntent.RevertDatabase -> enqueue { _state.value.database?.fileName?.let { openDatabase(it) } }
        }
    }

    private fun open(intent: AppDetailsIntent.Open) {
        val serial = currentSerial.value.selectedSerialOrNull ?: return
        val myGeneration = ++generation
        _state.value = AppDetailsState(serial = serial, packageName = intent.packageName, label = intent.label, icon = intent.icon)
        scope.launch(dispatchers.io) {
            work.withLock {
                closeSession()
                val dump = transport.executeText(PackageMetadataCommand.request(serial, intent.packageName))
                val pid = transport.executeText(AdbDeviceRequest(serial, AdbOperation.Shell(LogcatPidCommand.pidOf(intent.packageName))))
                if (myGeneration != generation) return@withLock
                _state.update {
                    it.copy(
                        details = if (dump.completed()) AppDetailsParser.parse(intent.packageName, dump.stdout) else null,
                        detailsError = if (dump.completed()) null else "dumpsys package failed: ${dump.outcome}",
                        runningPid = pid.stdout.trim().takeIf { pid.completed() && it.isNotEmpty() },
                    )
                }
                val access = probeAccess(serial, intent.packageName)
                if (myGeneration != generation) return@withLock
                if (access == null) {
                    _state.update { it.copy(access = FileAccessState.Unavailable(NO_ACCESS)) }
                    return@withLock
                }
                val listing = AppDataCommands.parseListing(transport.executeText(inApp(serial, access, intent.packageName, AppDataCommands.LIST_COMMAND)))
                if (myGeneration != generation) return@withLock
                _state.update {
                    it.copy(access = FileAccessState.Available(access), sharedPrefsFiles = listing.sharedPrefs, databaseFiles = listing.databases)
                }
            }
        }
    }

    private suspend fun probeAccess(serial: DeviceSerial, packageName: String): AppDataAccess? = when {
        AppDataCommands.isRootUid(transport.executeText(AppDataCommands.shellUidRequest(serial))) -> AppDataAccess.RootShell
        AppDataCommands.isNumericUid(transport.executeText(AppDataCommands.runAsProbeRequest(serial, packageName))) -> AppDataAccess.RunAs
        AppDataCommands.isRootUid(transport.executeText(AppDataCommands.suProbeRequest(serial))) -> AppDataAccess.Su
        else -> null
    }

    // ---- SharedPreferences ----

    private suspend fun openPrefs(fileName: String) {
        val (serial, packageName, access) = target() ?: return
        _state.update { it.copy(prefs = PrefsEditorState(fileName), notice = null) }
        val read = transport.executeText(inApp(serial, access, packageName, AppDataCommands.catCommand("shared_prefs/$fileName")))
        val parsed = if (read.completed()) SharedPrefsXml.parse(read.stdout) else SharedPrefsParse.Malformed("could not read the file: ${read.outcome}")
        if (_state.value.prefs?.fileName != fileName) return
        loadedPrefs = (parsed as? SharedPrefsParse.Parsed)?.entries.orEmpty()
        _state.update {
            it.copy(
                prefs = PrefsEditorState(
                    fileName = fileName,
                    entries = loadedPrefs,
                    loading = false,
                    error = (parsed as? SharedPrefsParse.Malformed)?.let { m -> "Not a SharedPreferences file (${m.reason})" },
                ),
            )
        }
    }

    private fun putPref(intent: AppDetailsIntent.PutPref) {
        val key = intent.key.trim()
        val value = PrefValue.fromInput(intent.type, intent.valueInput)
        val entries = _state.value.prefs?.entries ?: return
        val error = when {
            key.isEmpty() -> "The key cannot be empty"
            value == null -> "\"${intent.valueInput}\" is not a valid ${intent.type.label}"
            key != intent.originalKey && entries.any { it.key == key } -> "\"$key\" already exists"
            else -> null
        }
        if (error != null) {
            _state.update { s -> s.copy(prefs = s.prefs?.copy(error = error)) }
            return
        }
        updatePrefs { current ->
            val entry = PrefEntry(key, value!!)
            val index = current.indexOfFirst { it.key == intent.originalKey }
            if (intent.originalKey == null || index < 0) current + entry else current.toMutableList().apply { set(index, entry) }
        }
    }

    private fun updatePrefs(transform: (List<PrefEntry>) -> List<PrefEntry>) {
        _state.update { s -> s.copy(prefs = s.prefs?.let { it.copy(entries = transform(it.entries), dirty = true, error = null) }) }
    }

    private suspend fun savePrefs() {
        val (serial, packageName, access) = target() ?: return
        val prefs = _state.value.prefs ?: return
        _state.update { it.copy(prefs = prefs.copy(saving = true, error = null)) }
        stopApp(serial, packageName)
        val xml = SharedPrefsXml.serialize(prefs.entries).encodeToByteArray()
        // Stop at the first failing step; none of the write steps prints anything on success.
        var failure: AdbTextResult? = null
        for (command in AppDataCommands.writeCommands("shared_prefs/${prefs.fileName}", xml)) {
            val result = transport.executeText(inApp(serial, access, packageName, command))
            if (!result.completed() || result.stderr.isNotBlank() || result.stdout.isNotBlank()) {
                failure = result
                break
            }
        }
        if (failure != null) {
            _state.update { s -> s.copy(prefs = s.prefs?.copy(saving = false, error = "Save failed: ${(failure.stderr + failure.stdout).trim().ifEmpty { failure.outcome.toString() }}")) }
            return
        }
        openPrefs(prefs.fileName)
        _state.update { it.copy(notice = "Saved ${prefs.fileName} — the app was stopped so it reloads the new values") }
    }

    // ---- Databases ----

    private suspend fun openDatabase(fileName: String) {
        val (serial, packageName, access) = target() ?: return
        closeSession()
        _state.update { it.copy(database = DatabaseEditorState(fileName), notice = null) }
        when (val copy = databases.pull(serial, access, packageName, fileName)) {
            is DatabaseCopy.Failed -> _state.update { s -> s.copy(database = s.database?.copy(loading = false, error = copy.reason)) }
            is DatabaseCopy.Pulled -> {
                val opened = runCatching { sqlite.open(copy.localPath) }
                val newSession = opened.getOrElse { failure ->
                    _state.update { s -> s.copy(database = s.database?.copy(loading = false, error = "Not a readable SQLite database: ${failure.message}")) }
                    return
                }
                session = newSession
                sessionPath = copy.localPath
                val tables = newSession.tables()
                _state.update { s -> s.copy(database = s.database?.copy(tables = tables, loading = false)) }
                tables.firstOrNull()?.let { showPage(it, 0) }
            }
        }
    }

    private suspend fun showPage(table: String, offset: Long) {
        val current = session ?: return
        val page = current.page(table, offset, PAGE_SIZE)
        _state.update { s -> s.copy(database = s.database?.copy(table = table, page = page, offset = offset, loading = false)) }
    }

    private suspend fun editCell(intent: AppDetailsIntent.EditCell) {
        val current = session ?: return
        val database = _state.value.database ?: return
        val table = database.table ?: return
        val rowId = database.page?.rowIds?.getOrNull(intent.rowIndex)
        if (rowId == null) {
            _state.update { s -> s.copy(database = s.database?.copy(error = "Rows of $table cannot be edited here (the table has no rowid)")) }
            return
        }
        val error = current.updateCell(table, rowId, intent.column, intent.value)
        _state.update { s -> s.copy(database = s.database?.copy(dirty = s.database.dirty || error == null, error = error)) }
        showPage(table, database.offset)
    }

    private suspend fun runSql(sql: String) {
        val current = session ?: return
        val result = current.execute(sql, MAX_QUERY_ROWS)
        _state.update { s ->
            s.copy(database = s.database?.copy(queryResult = result, dirty = s.database.dirty || result is SqlResult.Updated, error = null))
        }
        _state.value.database?.let { db -> db.table?.let { showPage(it, db.offset) } }
        if (result is SqlResult.Updated) {
            // Statements may have created or dropped tables.
            val tables = current.tables()
            _state.update { s -> s.copy(database = s.database?.copy(tables = tables)) }
        }
    }

    private suspend fun saveDatabase() {
        val (serial, packageName, access) = target() ?: return
        val current = session ?: return
        val path = sessionPath ?: return
        val database = _state.value.database ?: return
        _state.update { s -> s.copy(database = s.database?.copy(saving = true, error = null)) }
        stopApp(serial, packageName)
        current.checkpoint()
        val error = databases.push(serial, access, packageName, database.fileName, path)
        _state.update { s ->
            s.copy(
                database = s.database?.copy(saving = false, dirty = error != null && database.dirty, error = error),
                notice = if (error == null) "Saved ${database.fileName} — the app was stopped so it reopens the new data" else null,
            )
        }
    }

    // ---- shared ----

    private fun enqueue(block: suspend () -> Unit) {
        val myGeneration = generation
        scope.launch(dispatchers.io) {
            work.withLock { if (myGeneration == generation) block() }
        }
    }

    private data class Target(val serial: DeviceSerial, val packageName: String, val access: AppDataAccess)

    private fun target(): Target? {
        val s = _state.value
        val access = (s.access as? FileAccessState.Available)?.access ?: return null
        return Target(s.serial ?: return null, s.packageName ?: return null, access)
    }

    private suspend fun stopApp(serial: DeviceSerial, packageName: String) {
        transport.executeText(AdbDeviceRequest(serial, AdbOperation.Shell(AppLifecycleCommands.forceStop(packageName))))
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
        sessionPath = null
    }

    private fun inApp(serial: DeviceSerial, access: AppDataAccess, packageName: String, inner: String) =
        AppDataCommands.inApp(serial, access, packageName, inner)

    private fun AdbTextResult.completed(): Boolean {
        val result = outcome
        return result is AdbOutcome.Completed && (result.exitCode == null || result.exitCode == 0)
    }
}
