package io.github.dkej123.devicecockpit.adapters.jvm.sqlite

import io.github.dkej123.devicecockpit.domain.appdata.SqlResult
import io.github.dkej123.devicecockpit.domain.appdata.SqlRows
import io.github.dkej123.devicecockpit.domain.appdata.SqlValue
import io.github.dkej123.devicecockpit.domain.appdata.SqliteEngine
import io.github.dkej123.devicecockpit.domain.appdata.SqliteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * [SqliteEngine] over the xerial SQLite JDBC driver. Every call runs on [Dispatchers.IO] and each
 * session holds one connection, used from one caller at a time (the app-details view model).
 */
class JdbcSqliteEngine : SqliteEngine {
    override suspend fun open(localPath: String): SqliteSession = withContext(Dispatchers.IO) {
        // The driver registers itself through ServiceLoader, which an IDE plugin classloader may
        // not see; load it explicitly with this class's loader.
        Class.forName("org.sqlite.JDBC", true, JdbcSqliteEngine::class.java.classLoader)
        JdbcSqliteSession(DriverManager.getConnection("jdbc:sqlite:$localPath"))
    }
}

private class JdbcSqliteSession(private val connection: Connection) : SqliteSession {

    override suspend fun tables(): List<String> = io {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    override suspend fun page(table: String, offset: Long, limit: Int): SqlRows = io {
        val quoted = quoteIdentifier(table)
        val total = connection.createStatement().use { it.executeQuery("SELECT count(*) FROM $quoted").use { rs -> rs.next(); rs.getLong(1) } }
        val withRowId = try {
            select("SELECT rowid AS __adbtoolbox_rowid, * FROM $quoted LIMIT $limit OFFSET $offset", rowIdColumn = true)
        } catch (_: SQLException) {
            // WITHOUT ROWID tables (and views) have no rowid: readable, not editable.
            select("SELECT * FROM $quoted LIMIT $limit OFFSET $offset", rowIdColumn = false)
        }
        withRowId.copy(totalRows = total)
    }

    override suspend fun updateCell(table: String, rowId: Long, column: String, value: String?): String? = io {
        try {
            connection.prepareStatement("UPDATE ${quoteIdentifier(table)} SET ${quoteIdentifier(column)} = ? WHERE rowid = ?").use { statement ->
                // Bound as text: SQLite's column affinity turns "42" into an INTEGER for an INTEGER column.
                if (value == null) statement.setNull(1, Types.NULL) else statement.setString(1, value)
                statement.setLong(2, rowId)
                statement.executeUpdate()
            }
            null
        } catch (failure: SQLException) {
            failure.message ?: "update failed"
        }
    }

    override suspend fun execute(sql: String, maxRows: Int): SqlResult = io {
        try {
            connection.createStatement().use { statement ->
                statement.maxRows = maxRows
                if (statement.execute(sql)) {
                    statement.resultSet.use { SqlResult.Rows(read(it, rowIdColumn = false)) }
                } else {
                    SqlResult.Updated(statement.updateCount)
                }
            }
        } catch (failure: SQLException) {
            SqlResult.Failed(failure.message ?: "SQL failed")
        }
    }

    override suspend fun checkpoint() {
        io {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
    }

    override fun close() {
        connection.close()
    }

    private fun select(sql: String, rowIdColumn: Boolean): SqlRows =
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { read(it, rowIdColumn) } }

    private fun read(rows: ResultSet, rowIdColumn: Boolean): SqlRows {
        val meta = rows.metaData
        val first = if (rowIdColumn) 2 else 1
        val columns = (first..meta.columnCount).map { meta.getColumnLabel(it) }
        val values = mutableListOf<List<SqlValue>>()
        val rowIds = mutableListOf<Long>()
        while (rows.next()) {
            if (rowIdColumn) rowIds += rows.getLong(1)
            values += (first..meta.columnCount).map { index -> valueAt(rows, index) }
        }
        return SqlRows(columns, values, if (rowIdColumn) rowIds else null)
    }

    private fun valueAt(rows: ResultSet, index: Int): SqlValue {
        val raw = rows.getObject(index) ?: return SqlValue.Null
        return when (raw) {
            is Int -> SqlValue.Integer(raw.toLong())
            is Long -> SqlValue.Integer(raw)
            is Double -> SqlValue.Real(raw)
            is Float -> SqlValue.Real(raw.toDouble())
            is ByteArray -> SqlValue.Blob(raw.size)
            else -> SqlValue.Text(raw.toString())
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

internal fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
