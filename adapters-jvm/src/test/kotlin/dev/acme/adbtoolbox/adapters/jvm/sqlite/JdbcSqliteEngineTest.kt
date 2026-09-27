package dev.acme.adbtoolbox.adapters.jvm.sqlite

import dev.acme.adbtoolbox.domain.appdata.SqlResult
import dev.acme.adbtoolbox.domain.appdata.SqlValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class JdbcSqliteEngineTest {

    @TempDir
    lateinit var dir: Path

    private fun database(): String {
        val path = dir.resolve("app.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, score REAL, avatar BLOB)")
                statement.execute("INSERT INTO users VALUES (1, 'Ann', 1.5, x'0102'), (2, NULL, 2.0, NULL)")
                statement.execute("CREATE TABLE \"odd \"\"name\" (k TEXT PRIMARY KEY, v INTEGER) WITHOUT ROWID")
                statement.execute("INSERT INTO \"odd \"\"name\" VALUES ('a', 1)")
            }
        }
        return path
    }

    @Test
    fun `lists tables and pages rows with their types`() = runTest {
        val session = JdbcSqliteEngine().open(database())

        session.tables() shouldBe listOf("odd \"name", "users")
        val page = session.page("users", offset = 0, limit = 10)
        page.columns shouldBe listOf("id", "name", "score", "avatar")
        page.rows shouldBe listOf(
            listOf(SqlValue.Integer(1), SqlValue.Text("Ann"), SqlValue.Real(1.5), SqlValue.Blob(2)),
            listOf(SqlValue.Integer(2), SqlValue.Null, SqlValue.Real(2.0), SqlValue.Null),
        )
        page.rowIds shouldBe listOf(1L, 2L)
        page.totalRows shouldBe 2L
        session.close()
    }

    @Test
    fun `WITHOUT ROWID tables are readable but not editable`() = runTest {
        val session = JdbcSqliteEngine().open(database())

        val page = session.page("odd \"name", offset = 0, limit = 10)

        page.rows shouldBe listOf(listOf(SqlValue.Text("a"), SqlValue.Integer(1)))
        page.rowIds shouldBe null
        session.close()
    }

    @Test
    fun `a cell edit uses column affinity and NULL is explicit`() = runTest {
        val session = JdbcSqliteEngine().open(database())

        session.updateCell("users", rowId = 2, column = "score", value = "7") shouldBe null
        session.updateCell("users", rowId = 1, column = "name", value = null) shouldBe null

        val rows = session.page("users", 0, 10).rows
        rows[1][2] shouldBe SqlValue.Real(7.0)
        rows[0][1] shouldBe SqlValue.Null
        session.updateCell("users", rowId = 1, column = "missing", value = "x")?.contains("missing") shouldBe true
        session.close()
    }

    @Test
    fun `custom SQL returns rows, counts, or the error`() = runTest {
        val session = JdbcSqliteEngine().open(database())

        (session.execute("SELECT name FROM users WHERE id = 1", 100) as SqlResult.Rows).rows.rows shouldBe listOf(listOf(SqlValue.Text("Ann")))
        session.execute("DELETE FROM users WHERE id = 2", 100) shouldBe SqlResult.Updated(1)
        session.execute("SELEKT", 100).shouldBeInstanceOf<SqlResult.Failed>()
        session.close()
    }

    @Test
    fun `checkpoint folds the write-ahead log into the main file`() = runTest {
        val path = database()
        val session = JdbcSqliteEngine().open(path)
        session.updateCell("users", 1, "name", "Bob")

        session.checkpoint()

        java.io.File("$path-wal").let { !it.exists() || it.length() == 0L } shouldBe true
        session.close()
    }
}
