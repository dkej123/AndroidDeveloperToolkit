package dev.acme.adbtoolbox.adapters.adb.appdata

import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.appdata.AppDataAccess
import dev.acme.adbtoolbox.domain.appdata.DatabaseCopy
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private val SERIAL = DeviceSerial.of("emulator-5554")
private val SQLITE_BYTES = "SQLite format 3\u0000rest-of-file".toByteArray()

private fun ok(stdout: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

class AdbAppDatabaseTransferTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `pull streams the database and its WAL through run-as into a local directory`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { ok("databases/app.db-wal") },
            binaryScript = { AdbBinaryScript(listOf(SQLITE_BYTES), AdbOutcome.Completed(0)) },
        )

        val copy = AdbAppDatabaseTransfer(transport) { dir }.pull(SERIAL, AppDataAccess.RunAs, "com.acme.shop", "app.db")

        copy shouldBe DatabaseCopy.Pulled(File(dir, "app.db").absolutePath)
        File(dir, "app.db").readBytes().contentEquals(SQLITE_BYTES) shouldBe true
        File(dir, "app.db-wal").exists() shouldBe true
        (transport.binaryRequests.first() as AdbDeviceRequest).operation shouldBe
            AdbOperation.Exec(listOf("run-as", "'com.acme.shop'", "cat", "'databases/app.db'"))
    }

    @Test
    fun `a cat error instead of a database is reported, not opened`() = runTest {
        val transport = FakeAdbTransport(
            binaryScript = { AdbBinaryScript(listOf("cat: databases/app.db: Permission denied".toByteArray()), AdbOutcome.Completed(0)) },
        )

        val copy = AdbAppDatabaseTransfer(transport) { dir }.pull(SERIAL, AppDataAccess.Su, "com.acme.shop", "app.db")

        copy.shouldBeInstanceOf<DatabaseCopy.Failed>().reason shouldContain "Permission denied"
    }

    @Test
    fun `push stages the file in data-local-tmp, copies it over the database and drops stale journals`() = runTest {
        val transport = FakeAdbTransport(textScript = { request ->
            if (((request as AdbDeviceRequest).operation as? AdbOperation.Shell)?.command?.render()?.contains("cat ") == true) ok("done") else ok()
        })
        val local = File(dir, "app.db").apply { writeBytes(SQLITE_BYTES) }

        val error = AdbAppDatabaseTransfer(transport) { dir }.push(SERIAL, AppDataAccess.RootShell, "com.acme.shop", "app.db", local.absolutePath)

        error shouldBe null
        val push = (transport.textRequests[0] as AdbDeviceRequest).operation as AdbOperation.Host
        push.arguments.take(2) shouldBe listOf("push", local.absolutePath)
        val copy = ((transport.textRequests[1] as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
        copy shouldContain "> '\\''databases/app.db'\\''"
        copy shouldContain "rm -f '\\''databases/app.db-wal'\\''"
        ((transport.textRequests[2] as AdbDeviceRequest).operation as AdbOperation.Shell).command.render() shouldContain "rm -f '/data/local/tmp/adb-toolbox-"
    }
}
