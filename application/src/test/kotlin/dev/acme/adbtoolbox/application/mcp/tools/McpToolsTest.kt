package dev.acme.adbtoolbox.application.mcp.tools

import dev.acme.adbtoolbox.application.appdetails.AppDataReader
import dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase
import dev.acme.adbtoolbox.application.apps.ClearDataUseCase
import dev.acme.adbtoolbox.application.apps.UninstallUseCase
import dev.acme.adbtoolbox.application.currentapp.CurrentAppUseCase
import dev.acme.adbtoolbox.application.layout.CaptureLayoutUseCase
import dev.acme.adbtoolbox.application.locale.DeviceLocaleUseCase
import dev.acme.adbtoolbox.application.locale.EmulatorLocationUseCase
import dev.acme.adbtoolbox.application.locale.InMemoryOriginalLocaleStore
import dev.acme.adbtoolbox.application.mcp.McpCallContext
import dev.acme.adbtoolbox.application.mcp.McpContent
import dev.acme.adbtoolbox.application.mcp.McpTool
import dev.acme.adbtoolbox.application.mcp.McpToolResult
import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.appdata.AppDataAccess
import dev.acme.adbtoolbox.domain.appdata.AppDatabaseTransfer
import dev.acme.adbtoolbox.domain.appdata.DatabaseCopy
import dev.acme.adbtoolbox.domain.appdata.SqlResult
import dev.acme.adbtoolbox.domain.appdata.SqlRows
import dev.acme.adbtoolbox.domain.appdata.SqlValue
import dev.acme.adbtoolbox.domain.appdata.SqliteEngine
import dev.acme.adbtoolbox.domain.appdata.SqliteSession
import dev.acme.adbtoolbox.domain.capture.MarkBox
import dev.acme.adbtoolbox.domain.capture.ScreenMark
import dev.acme.adbtoolbox.domain.capture.ScreenMarker
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.locale.DeviceLocalePort
import dev.acme.adbtoolbox.domain.locale.LocaleAction
import dev.acme.adbtoolbox.domain.locale.LocaleRead
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("emulator-5554")
private val SETTINGS_DUMP = McpToolsTest::class.java.getResource("/layout/api30-settings.xml")!!.readText()

private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

private fun McpToolResult.text() = content.filterIsInstance<McpContent.Text>().joinToString("\n") { it.text }

class McpToolsTest {
    private val commands = mutableListOf<String>()
    private var confirmAnswer = true
    private val confirmations = mutableListOf<String>()
    private var locale = "en-US"
    private var failDumps = false

    private val transport = FakeAdbTransport(
        textScript = { request ->
            val op = (request as AdbDeviceRequest).operation
            val line = when (op) {
                is AdbOperation.Shell -> op.command.render()
                is AdbOperation.Host -> op.arguments.joinToString(" ")
                is AdbOperation.Exec -> op.arguments.joinToString(" ")
            }
            commands += line
            fun ok(out: String = "") = AdbTextResult(AdbOutcome.Completed(0), out, "")
            when {
                line == "wm density" -> ok("Physical density: 440\n")
                line.startsWith("uiautomator dump") -> ok(if (failDumps) "" else SETTINGS_DUMP)
                line.startsWith("cmd package resolve-activity") -> ok("com.launcher/.Home")
                line.startsWith("dumpsys activity activities") -> ok("  topResumedActivity=ActivityRecord{1 u0 com.acme.shop/.Main t1}\n--adbtoolbox-window--\n")
                line.startsWith("dumpsys package") -> ok("")
                line.startsWith("pidof") -> ok("4242\n")
                line.startsWith("ps -o") -> ok("00:10")
                line.startsWith("logcat -d") -> ok(
                    "--------- beginning of main\n10-07 12:00:00.000  4242  4242 I Shop: started\n10-07 12:00:01.000  4242  4242 E Shop: payment failed\n",
                )
                line.startsWith("pm list packages") -> ok("package:com.acme.shop\npackage:com.other\n")
                line.startsWith("pm clear") -> ok("Success\n")
                line.startsWith("cmd uimode night") -> ok("Night mode: yes\n")
                line.startsWith("id -u") -> ok("2000\n")
                line.startsWith("run-as") && "id -u" in line -> ok("10123\n")
                line.startsWith("run-as") && "ls -1" in line -> ok("prefs.xml\n---\nshop.db\n")
                line.startsWith("run-as") && "cat" in line -> ok("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"token\">abc</string><boolean name=\"onboarded\" value=\"true\" /></map>")
                line.startsWith("su") -> AdbTextResult(AdbOutcome.Completed(1), "", "su: not found")
                else -> ok()
            }
        },
        binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1, 2, 3)), AdbOutcome.Completed(0)) },
    )

    private val localePort = object : DeviceLocalePort {
        override suspend fun run(serial: DeviceSerial, action: LocaleAction): LocaleRead {
            if (action is LocaleAction.Set) locale = action.locales.first().value
            return LocaleRead.Locales(listOf(locale))
        }
    }
    private val sqlite = object : SqliteEngine {
        override suspend fun open(localPath: String): SqliteSession = object : SqliteSession {
            override suspend fun tables() = listOf("orders")
            override suspend fun page(table: String, offset: Long, limit: Int) = SqlRows(emptyList(), emptyList())
            override suspend fun updateCell(table: String, rowId: Long, column: String, value: String?): String? = null
            override suspend fun execute(sql: String, maxRows: Int): SqlResult = SqlResult.Rows(
                SqlRows(listOf("id", "total"), listOf(listOf(SqlValue.Integer(1), SqlValue.Real(9.5)))),
            )
            override suspend fun checkpoint() = Unit
            override fun close() = Unit
        }
    }
    private val transfer = object : AppDatabaseTransfer {
        override suspend fun pull(serial: DeviceSerial, access: AppDataAccess, packageName: String, fileName: String) = DatabaseCopy.Pulled("/tmp/$fileName")
        override suspend fun push(serial: DeviceSerial, access: AppDataAccess, packageName: String, fileName: String, localPath: String): String? = null
    }
    private var detected = listOf<MarkBox>()
    private val drawn = mutableListOf<ScreenMark>()
    private val marker = object : ScreenMarker {
        override fun detect(png: ByteArray) = detected
        override fun draw(png: ByteArray, marks: List<ScreenMark>): ByteArray {
            drawn += marks
            return byteArrayOf(9)
        }
    }
    private val devices = FakeDeviceRepository(listOf(Device(serial, DeviceConnectionState.Online, model = "Pixel_9")))
    private val selected = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(devices.devices.value.single()))

    private val env = McpToolEnvironment(
        transport = transport,
        devices = devices,
        selected = selected,
        layout = CaptureLayoutUseCase(transport),
        imageScaler = { png, w, h -> byteArrayOf(w.toByte(), h.toByte()) },
        marker = marker,
        lifecycle = AppLifecycleUseCase(transport),
        clearData = ClearDataUseCase(transport),
        uninstall = UninstallUseCase(transport),
        currentApp = CurrentAppUseCase(transport),
        locale = DeviceLocaleUseCase(localePort, InMemoryOriginalLocaleStore()),
        location = EmulatorLocationUseCase(transport),
        appData = AppDataReader(transport, transfer, sqlite),
        confirmation = { action, _, pkg, agent -> confirmations += "$action $pkg $agent"; confirmAnswer },
    )
    private val tools: Map<String, McpTool> = mcpTools(env).associateBy { it.name }
    private val context = McpCallContext("s1", "Claude Code")

    private suspend fun call(name: String, vararg pairs: Pair<String, Any>) = tools.getValue(name).call(args(*pairs), context)

    @Test
    fun `the catalog has every group and only reading tools are read-only`() {
        tools.keys shouldBe setOf(
            "screenshot", "get_ui", "accessibility_audit", "tap", "swipe", "type_text", "press_key",
            "list_devices", "get_device_state", "set_device_settings", "list_apps", "open_app", "manage_app", "logcat",
            "read_preferences", "query_database",
        )
        tools.values.filter { it.readOnly }.map { it.name }.toSet() shouldBe setOf(
            "screenshot", "get_ui", "accessibility_audit", "list_devices", "get_device_state", "list_apps", "logcat", "read_preferences", "query_database",
        )
        tools.getValue("manage_app").destructive shouldBe true
    }

    @Test
    fun `get_ui lists the screen in dp and tap by ref hits the element's centre in pixels`() = runTest {
        val ui = call("get_ui", "query" to "Display").text()
        ui shouldStartWith "Screen 393x753 dp, 440 dpi"
        val ref = Regex("""\[(\d+)] TextView "Display"""").find(ui)!!.groupValues[1].toInt()

        call("tap", "ref" to ref).text() shouldContain "Tapped [$ref] TextView \"Display\""
        commands.last() shouldBe "input tap 270 1568"
    }

    @Test
    fun `tap by text, by point, and argument errors`() = runTest {
        call("tap", "text" to "Battery").text() shouldContain "\"Battery\""
        call("tap", "x" to 100, "y" to 200).text() shouldBe "Tapped point at 100,200 dp."
        commands.last() shouldBe "input tap 275 550"
        call("tap", "x" to 1).text() shouldBe "Invalid arguments: Give both x and y."
        call("tap", "text" to "Nope").isError shouldBe true
    }

    @Test
    fun `screenshot is scaled to dp`() = runTest {
        val result = call("screenshot")
        val image = result.content.filterIsInstance<McpContent.Image>().single()
        java.util.Base64.getDecoder().decode(image.base64).toList() shouldBe listOf<Byte>(393.toByte(), 753.toByte())
    }

    @Test
    fun `typing and keys`() = runTest {
        call("type_text", "text" to "hello world", "submit" to true)
        commands.takeLast(2) shouldBe listOf("input text 'hello%sworld'", "input keyevent 66")
        call("type_text", "text" to "żółw").isError shouldBe true
        call("press_key", "key" to "back", "times" to 2)
        commands.takeLast(2) shouldBe listOf("input keyevent 4", "input keyevent 4")
    }

    @Test
    fun `swipe up over the screen moves content up`() = runTest {
        call("get_ui")
        call("swipe", "direction" to "up")
        commands.last() shouldBe "input swipe 540 1036 540 311 300" // 35 % of the screen height
    }

    @Test
    fun `settings change and the state is reported back`() = runTest {
        val text = call("set_device_settings", "dark_mode" to true, "locale" to "ar-XB", "location" to "52.2,21.0").text()

        commands shouldContain "cmd uimode night yes"
        commands shouldContain "emu geo fix 21.0 52.2"
        text shouldContain "locale: ar-XB (original en-US)"
        text shouldContain "app_in_front: com.acme.shop/.Main"
        call("set_device_settings", "locale" to "not a tag!").isError shouldBe true
    }

    @Test
    fun `clear data asks the user in the IDE and respects a refusal`() = runTest {
        confirmAnswer = false
        call("manage_app", "action" to "clear_data").text() shouldBe "Declined by user: clear data of com.acme.shop was not done."
        confirmations.single() shouldBe "ClearData com.acme.shop Claude Code"
        commands.none { it.startsWith("pm clear") } shouldBe true

        confirmAnswer = true
        call("manage_app", "action" to "clear_data", "package" to "com.acme.shop").text() shouldBe "Data cleared for com.acme.shop."
    }

    @Test
    fun `logcat filters by app, level and text`() = runTest {
        call("logcat", "package" to "com.acme.shop", "level" to "W", "contains" to "payment").text() shouldBe
            "10-07 12:00:01.000  4242  4242 E Shop: payment failed"
        commands.last() shouldBe "logcat -d -v threadtime --pid=4242 -t 5000 *:W"
    }

    @Test
    fun `preferences and read-only queries go through run-as on a copy`() = runTest {
        call("read_preferences").text() shouldBe "prefs.xml\n  token (String) = abc\n  onboarded (Boolean) = true"
        call("query_database", "database" to "shop.db", "sql" to "SELECT id, total FROM orders").text() shouldBe "id | total\n1 | 9.5\n(1 rows)"
        call("query_database", "database" to "shop.db", "sql" to "DELETE FROM orders").isError shouldBe true
    }

    @Test
    fun `devices are listed and an unknown serial is a tool error`() = runTest {
        call("list_devices").text() shouldBe "emulator-5554  Pixel 9  online  (selected)"
        call("get_ui", "serial" to "nope").let {
            it.isError shouldBe true
            it.text() shouldContain "not connected"
        }
        selected.value = SelectedDeviceState.None
        call("get_ui").text() shouldContain "No device is selected"
        call("list_apps", "serial" to "emulator-5554").text().lines() shouldNotContain "nothing"
    }

    @Test
    fun `an annotated screenshot labels tree elements by ref and uncovered shapes as marks`() = runTest {
        val first = call("get_ui", "interactive_only" to true).text().lines()[1]
        val (ref, x, y, w, h) = Regex("""^\[(\d+)].* @(\d+),(\d+) (\d+)x(\d+)""").find(first)!!.destructured
        detected = listOf(MarkBox(x.toInt(), y.toInt(), w.toInt(), h.toInt()), MarkBox(0, 0, 10, 10))

        val result = call("screenshot", "annotate" to true)

        java.util.Base64.getDecoder().decode(result.content.filterIsInstance<McpContent.Image>().single().base64).toList() shouldBe listOf<Byte>(9)
        drawn.first { it.fromTree }.label shouldBe ref
        drawn.filterNot { it.fromTree }.map { it.label to it.box } shouldBe listOf("m1" to MarkBox(0, 0, 10, 10))
        result.text() shouldContain "m1 @0,0 10x10"

        call("tap", "mark" to 1).text() shouldBe "Tapped mark m1 at 5,5 dp."
        commands.last() shouldBe "input tap 14 14"
        call("tap", "mark" to 7).text() shouldBe "Invalid arguments: mark 7 is not on the last annotated screenshot; take screenshot annotate=true again."
    }

    @Test
    fun `return_ui appends the interactive elements after the screen settles`() = runTest {
        val start = currentTime
        val text = call("tap", "x" to 100, "y" to 200, "return_ui" to true).text()

        text shouldStartWith "Tapped point at 100,200 dp.\n\nScreen 393x753 dp, 440 dpi\n["
        currentTime - start shouldBe 500
        call("press_key", "key" to "back").text() shouldBe "Pressed back."
    }

    @Test
    fun `an action still reports success when the UI after it cannot be read`() = runTest {
        failDumps = true
        call("press_key", "key" to "back", "return_ui" to true).text() shouldStartWith "Pressed back.\n\nUI not captured: uiautomator returned nothing"
    }
}
