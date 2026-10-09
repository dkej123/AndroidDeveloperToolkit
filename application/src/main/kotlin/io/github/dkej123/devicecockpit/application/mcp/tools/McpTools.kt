package io.github.dkej123.devicecockpit.application.mcp.tools

import io.github.dkej123.devicecockpit.application.mcp.McpTool

/** Every MCP tool, grouped See / Act / Device / Apps / Data (design §11). */
fun mcpTools(env: McpToolEnvironment): List<McpTool> = screenAndInputTools(env) + deviceAppDataTools(env)

/** Tool names and metadata only (Settings' "Exposed tools"), without a device or any use case behind them. */
fun mcpCatalog(): List<McpTool> = mcpTools(catalogEnvironment)

private val catalogEnvironment: McpToolEnvironment by lazy {
    val transport = io.github.dkej123.devicecockpit.domain.adb.FakeAdbTransport()
    McpToolEnvironment(
        transport = transport,
        devices = io.github.dkej123.devicecockpit.domain.device.FakeDeviceRepository(),
        selected = kotlinx.coroutines.flow.MutableStateFlow(io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState.None),
        layout = io.github.dkej123.devicecockpit.application.layout.CaptureLayoutUseCase(transport),
        imageScaler = { _, _, _ -> null },
        marker = object : io.github.dkej123.devicecockpit.domain.capture.ScreenMarker {
            override fun detect(png: ByteArray) = emptyList<io.github.dkej123.devicecockpit.domain.capture.MarkBox>()
            override fun draw(png: ByteArray, marks: List<io.github.dkej123.devicecockpit.domain.capture.ScreenMark>): ByteArray? = null
        },
        lifecycle = io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase(transport),
        clearData = io.github.dkej123.devicecockpit.application.apps.ClearDataUseCase(transport),
        uninstall = io.github.dkej123.devicecockpit.application.apps.UninstallUseCase(transport),
        currentApp = io.github.dkej123.devicecockpit.application.currentapp.CurrentAppUseCase(transport),
        locale = io.github.dkej123.devicecockpit.application.locale.DeviceLocaleUseCase(
            object : io.github.dkej123.devicecockpit.domain.locale.DeviceLocalePort {
                override suspend fun run(serial: io.github.dkej123.devicecockpit.domain.adb.DeviceSerial, action: io.github.dkej123.devicecockpit.domain.locale.LocaleAction) =
                    io.github.dkej123.devicecockpit.domain.locale.LocaleRead.Failed("catalog")
            },
            io.github.dkej123.devicecockpit.application.locale.InMemoryOriginalLocaleStore(),
        ),
        location = io.github.dkej123.devicecockpit.application.locale.EmulatorLocationUseCase(transport),
        appData = io.github.dkej123.devicecockpit.application.appdetails.AppDataReader(
            transport,
            object : io.github.dkej123.devicecockpit.domain.appdata.AppDatabaseTransfer {
                override suspend fun pull(serial: io.github.dkej123.devicecockpit.domain.adb.DeviceSerial, access: io.github.dkej123.devicecockpit.domain.appdata.AppDataAccess, packageName: String, fileName: String) =
                    io.github.dkej123.devicecockpit.domain.appdata.DatabaseCopy.Failed("catalog")

                override suspend fun push(serial: io.github.dkej123.devicecockpit.domain.adb.DeviceSerial, access: io.github.dkej123.devicecockpit.domain.appdata.AppDataAccess, packageName: String, fileName: String, localPath: String): String? = null
            },
            object : io.github.dkej123.devicecockpit.domain.appdata.SqliteEngine {
                override suspend fun open(localPath: String) = error("catalog")
            },
        ),
        confirmation = { _, _, _, _ -> false },
    )
}
