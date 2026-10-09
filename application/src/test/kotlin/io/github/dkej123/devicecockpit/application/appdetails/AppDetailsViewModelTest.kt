@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.dkej123.devicecockpit.application.appdetails

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport
import io.github.dkej123.devicecockpit.domain.appdata.AppDataAccess
import io.github.dkej123.devicecockpit.domain.appdata.AppDatabaseTransfer
import io.github.dkej123.devicecockpit.domain.appdata.DatabaseCopy
import io.github.dkej123.devicecockpit.domain.appdata.PrefEntry
import io.github.dkej123.devicecockpit.domain.appdata.PrefType
import io.github.dkej123.devicecockpit.domain.appdata.PrefValue
import io.github.dkej123.devicecockpit.domain.appdata.SqlResult
import io.github.dkej123.devicecockpit.domain.appdata.SqlRows
import io.github.dkej123.devicecockpit.domain.appdata.SqlValue
import io.github.dkej123.devicecockpit.domain.appdata.SqliteEngine
import io.github.dkej123.devicecockpit.domain.appdata.SqliteSession
import io.github.dkej123.devicecockpit.domain.device.Device
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Test

private val SERIAL = DeviceSerial.of("emulator-5554")
private const val PKG = "com.acme.shop"

private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private fun ok(stdout: String = "") = AdbTextResult(AdbOutcome.Completed(0), stdout, "")

private const val PREFS = "<map><int name=\"launches\" value=\"3\" /><string name=\"user\">ann</string></map>"

/** Routes by the rendered command; [log] keeps every command in order. */
private class FakeDevice(var root: Boolean = true, var runAs: Boolean = false) {
    val log = mutableListOf<String>()
    var prefsFile = PREFS
    var packageDump = "Packages:\n  Package [$PKG] (1):\n    versionName=2.0\n    flags=[ DEBUGGABLE ]\n"
    val transport = FakeAdbTransport(textScript = { request ->
        val op = (request as AdbDeviceRequest).operation
        val line = when (op) {
            is AdbOperation.Shell -> op.command.render()
            is AdbOperation.Host -> op.arguments.joinToString(" ")
            is AdbOperation.Exec -> op.arguments.joinToString(" ")
        }
        log += line
        when {
            line == "id -u" -> ok(if (root) "0" else "2000")
            line.startsWith("run-as '$PKG' id -u") -> ok(if (runAs) "10081" else "run-as: package not debuggable: $PKG")
            line == "su 0 id -u" -> ok("/system/bin/sh: su: not found")
            line == "am get-current-user" -> ok("0")
            line.startsWith("pm grant --user") -> { packageDump = packageDump.replace("granted=false", "granted=true"); ok() }
            line.startsWith("pm revoke --user") -> { packageDump = packageDump.replace("granted=true", "granted=false"); ok() }
            line.startsWith("dumpsys package") -> ok(packageDump)
            line.startsWith("pidof") -> ok("4242")
            line.contains("ls -1 shared_prefs") -> ok("prefs.xml\n---\napp.db\napp.db-wal\n")
            line.contains("cat '\\''shared_prefs/prefs.xml'\\''") -> ok(prefsFile)
            else -> ok()
        }
    })
}

private class FakeSession : SqliteSession {
    val cells = mutableMapOf(1L to "Ann", 2L to "Bob")
    var checkpoints = 0
    override suspend fun tables() = listOf("users")
    override suspend fun page(table: String, offset: Long, limit: Int) =
        SqlRows(listOf("name"), cells.values.map { listOf(SqlValue.Text(it)) }, rowIds = cells.keys.toList(), totalRows = cells.size.toLong())
    override suspend fun updateCell(table: String, rowId: Long, column: String, value: String?): String? {
        cells[rowId] = value ?: "NULL"
        return null
    }
    override suspend fun execute(sql: String, maxRows: Int): SqlResult = SqlResult.Updated(1)
    override suspend fun checkpoint() {
        checkpoints++
    }
    override fun close() = Unit
}

private class FakeTransfer(private val log: MutableList<String>) : AppDatabaseTransfer {
    override suspend fun pull(serial: DeviceSerial, access: AppDataAccess, packageName: String, fileName: String): DatabaseCopy {
        log += "pull $fileName via $access"
        return DatabaseCopy.Pulled("/tmp/$fileName")
    }
    override suspend fun push(serial: DeviceSerial, access: AppDataAccess, packageName: String, fileName: String, localPath: String): String? {
        log += "push $localPath"
        return null
    }
}

class AppDetailsViewModelTest {

    private class Harness(val device: FakeDevice = FakeDevice()) {
        val scope = TestScope()
        val sqliteSession = FakeSession()
        val viewModel = AppDetailsViewModel(
            scope = scope,
            dispatchers = TestDispatchers(StandardTestDispatcher(scope.testScheduler)),
            transport = device.transport,
            databases = FakeTransfer(device.log),
            sqlite = object : SqliteEngine {
                override suspend fun open(localPath: String): SqliteSession = sqliteSession
            },
            selectedDeviceState = MutableStateFlow(SelectedDeviceState.Online(Device(SERIAL, DeviceConnectionState.Online))),
        )

        fun open() {
            viewModel.handle(AppDetailsIntent.Open(PKG, "Shop", null))
            scope.advanceUntilIdle()
        }

        fun state() = viewModel.state.value
    }

    @Test
    fun `opening an app loads its details, process and files through the root shell`() {
        val h = Harness()
        h.open()

        h.state().details?.versionName shouldBe "2.0"
        h.state().details?.isDebuggable shouldBe true
        h.state().runningPid shouldBe "4242"
        h.state().access shouldBe FileAccessState.Available(AppDataAccess.RootShell)
        h.state().sharedPrefsFiles shouldBe listOf("prefs.xml")
        h.state().databaseFiles shouldBe listOf("app.db")
    }

    @Test
    fun `a debuggable app on a normal phone is reached through run-as`() {
        val h = Harness(FakeDevice(root = false, runAs = true))
        h.open()

        h.state().access shouldBe FileAccessState.Available(AppDataAccess.RunAs)
    }

    @Test
    fun `without root, run-as or su the files are explained as unavailable`() {
        val h = Harness(FakeDevice(root = false, runAs = false))
        h.open()

        h.state().access.shouldBeInstanceOf<FileAccessState.Unavailable>().reason shouldContain "debuggable"
        h.state().sharedPrefsFiles shouldBe emptyList()
    }

    @Test
    fun `prefs are edited locally, then saved after stopping the app`() {
        val h = Harness()
        h.open()
        h.viewModel.handle(AppDetailsIntent.OpenPrefs("prefs.xml"))
        h.scope.advanceUntilIdle()
        h.state().prefs?.entries shouldBe listOf(PrefEntry("launches", PrefValue.IntValue(3)), PrefEntry("user", PrefValue.Text("ann")))

        h.viewModel.handle(AppDetailsIntent.PutPref("launches", "launches", PrefType.Int, "9"))
        h.viewModel.handle(AppDetailsIntent.PutPref(null, "flag", PrefType.Boolean, "true"))
        h.state().prefs?.dirty shouldBe true
        h.device.log.clear()
        h.viewModel.handle(AppDetailsIntent.SavePrefs)
        h.scope.advanceUntilIdle()

        h.device.log.first() shouldBe "am force-stop '$PKG'"
        h.device.log.any { it.contains("base64 -d > ") } shouldBe true
        h.device.log.any { it.contains("> '\\''shared_prefs/prefs.xml'\\''") } shouldBe true
        h.state().notice!! shouldContain "Saved prefs.xml"
    }

    @Test
    fun `an invalid value or duplicate key is rejected without touching the entries`() {
        val h = Harness()
        h.open()
        h.viewModel.handle(AppDetailsIntent.OpenPrefs("prefs.xml"))
        h.scope.advanceUntilIdle()

        h.viewModel.handle(AppDetailsIntent.PutPref("launches", "launches", PrefType.Int, "many"))
        h.state().prefs?.error shouldBe "\"many\" is not a valid Int"
        h.viewModel.handle(AppDetailsIntent.PutPref(null, "user", PrefType.Text, "x"))
        h.state().prefs?.error shouldBe "\"user\" already exists"
        h.state().prefs?.dirty shouldBe false
    }

    @Test
    fun `a database is pulled, edited locally, then checkpointed and pushed after stopping the app`() {
        val h = Harness()
        h.open()
        h.viewModel.handle(AppDetailsIntent.OpenDatabase("app.db"))
        h.scope.advanceUntilIdle()
        h.state().database?.table shouldBe "users"

        h.viewModel.handle(AppDetailsIntent.EditCell(rowIndex = 1, column = "name", value = "Bea"))
        h.scope.advanceUntilIdle()
        h.state().database?.dirty shouldBe true
        h.state().database?.page?.rows?.get(1) shouldBe listOf(SqlValue.Text("Bea"))

        h.device.log.clear()
        h.viewModel.handle(AppDetailsIntent.SaveDatabase)
        h.scope.advanceUntilIdle()

        h.device.log shouldContainInOrder listOf("am force-stop '$PKG'", "push /tmp/app.db")
        h.sqliteSession.checkpoints shouldBe 1
        h.state().database?.dirty shouldBe false
    }

    @Test
    fun `closing drops the open app`() {
        val h = Harness()
        h.open()

        h.viewModel.handle(AppDetailsIntent.Close)
        h.scope.advanceUntilIdle()

        h.state().isOpen shouldBe false
    }

    @Test
    fun `grant executes for current user and trusts only refreshed dumpsys state`() {
        val h = Harness()
        h.device.packageDump = """
            Packages:
              Package [$PKG] (1):
                requested permissions:
                  android.permission.CAMERA
                User 0: installed=true
                  runtime permissions:
                    android.permission.CAMERA: granted=false, flags=[ USER_SET ]
        """.trimIndent()
        h.open()
        h.device.log.clear()

        h.viewModel.handle(AppDetailsIntent.GrantPermission("android.permission.CAMERA"))
        h.scope.advanceUntilIdle()

        h.device.log.take(2) shouldBe listOf(
            "pm grant --user '0' '$PKG' 'android.permission.CAMERA'",
            "dumpsys package '$PKG'",
        )
        h.state().details?.permissions?.single()?.state shouldBe io.github.dkej123.devicecockpit.domain.appdata.PermissionState.GRANTED
    }
}
