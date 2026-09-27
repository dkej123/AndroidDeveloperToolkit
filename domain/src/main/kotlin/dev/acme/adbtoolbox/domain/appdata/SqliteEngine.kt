package dev.acme.adbtoolbox.domain.appdata

/** One cell as read from SQLite. [Blob] keeps only its size: blobs are shown, never edited. */
sealed interface SqlValue {
    val display: String

    data object Null : SqlValue {
        override val display get() = "NULL"
    }

    data class Integer(val value: Long) : SqlValue {
        override val display get() = value.toString()
    }

    data class Real(val value: Double) : SqlValue {
        override val display get() = value.toString()
    }

    data class Text(val value: String) : SqlValue {
        override val display get() = value
    }

    data class Blob(val size: Int) : SqlValue {
        override val display get() = "BLOB ($size bytes)"
    }
}

/**
 * A page of rows. [rowIds] is set when the rows come from one table that has a `rowid`, which is
 * what makes them editable ([SqliteSession.updateCell]); query results and `WITHOUT ROWID` tables
 * are read-only.
 */
data class SqlRows(
    val columns: List<String>,
    val rows: List<List<SqlValue>>,
    val rowIds: List<Long>? = null,
    val totalRows: Long? = null,
)

/** The outcome of arbitrary SQL: rows for a query, an affected-row count for a statement. */
sealed interface SqlResult {
    data class Rows(val rows: SqlRows) : SqlResult

    data class Updated(val count: Int) : SqlResult

    data class Failed(val message: String) : SqlResult
}

/** An open local copy of an app database (see [SqliteEngine]). */
interface SqliteSession {
    suspend fun tables(): List<String>

    suspend fun page(table: String, offset: Long, limit: Int): SqlRows

    /** Sets one cell by rowid; `null` stores SQL NULL. Returns an error message, or `null` on success. */
    suspend fun updateCell(table: String, rowId: Long, column: String, value: String?): String?

    suspend fun execute(sql: String, maxRows: Int): SqlResult

    /** Folds any write-ahead log into the main file so that single file is the whole database. */
    suspend fun checkpoint()

    fun close()
}

/** Opens database files on the host — app databases are pulled from the device first. */
interface SqliteEngine {
    suspend fun open(localPath: String): SqliteSession
}

/** A database pulled to the host: [localPath] is the main file; its `-wal` (if any) sits next to it. */
sealed interface DatabaseCopy {
    data class Pulled(val localPath: String) : DatabaseCopy

    data class Failed(val reason: String) : DatabaseCopy
}

/** Moves an app's `databases/` files between the device and the host. */
interface AppDatabaseTransfer {
    suspend fun pull(serial: dev.acme.adbtoolbox.domain.adb.DeviceSerial, access: AppDataAccess, packageName: String, fileName: String): DatabaseCopy

    /**
     * Replaces the device's `databases/<fileName>` with [localPath] (checkpointed first, so it is
     * the whole database) and removes the device's stale `-wal`/`-shm`/`-journal`. Returns an
     * error message, or `null` on success.
     */
    suspend fun push(
        serial: dev.acme.adbtoolbox.domain.adb.DeviceSerial,
        access: AppDataAccess,
        packageName: String,
        fileName: String,
        localPath: String,
    ): String?
}
