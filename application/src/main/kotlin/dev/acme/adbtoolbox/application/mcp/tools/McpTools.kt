package dev.acme.adbtoolbox.application.mcp.tools

import dev.acme.adbtoolbox.application.mcp.McpTool

/** Every MCP tool, grouped See / Act / Device / Apps / Data (design §11). */
fun mcpTools(env: McpToolEnvironment): List<McpTool> = screenAndInputTools(env) + deviceAppDataTools(env)

/** Tool names and metadata only (Settings' "Exposed tools"), without a device or any use case behind them. */
fun mcpCatalog(): List<McpTool> = mcpTools(catalogEnvironment)

private val catalogEnvironment: McpToolEnvironment by lazy {
    val transport = dev.acme.adbtoolbox.domain.adb.FakeAdbTransport()
    McpToolEnvironment(
        transport = transport,
        devices = dev.acme.adbtoolbox.domain.device.FakeDeviceRepository(),
        selected = kotlinx.coroutines.flow.MutableStateFlow(dev.acme.adbtoolbox.domain.device.SelectedDeviceState.None),
        layout = dev.acme.adbtoolbox.application.layout.CaptureLayoutUseCase(transport),
        imageScaler = { _, _, _ -> null },
        lifecycle = dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase(transport),
        clearData = dev.acme.adbtoolbox.application.apps.ClearDataUseCase(transport),
        uninstall = dev.acme.adbtoolbox.application.apps.UninstallUseCase(transport),
        currentApp = dev.acme.adbtoolbox.application.currentapp.CurrentAppUseCase(transport),
        locale = dev.acme.adbtoolbox.application.locale.DeviceLocaleUseCase(
            object : dev.acme.adbtoolbox.domain.locale.DeviceLocalePort {
                override suspend fun run(serial: dev.acme.adbtoolbox.domain.adb.DeviceSerial, action: dev.acme.adbtoolbox.domain.locale.LocaleAction) =
                    dev.acme.adbtoolbox.domain.locale.LocaleRead.Failed("catalog")
            },
            dev.acme.adbtoolbox.application.locale.InMemoryOriginalLocaleStore(),
        ),
        location = dev.acme.adbtoolbox.application.locale.EmulatorLocationUseCase(transport),
        appData = dev.acme.adbtoolbox.application.appdetails.AppDataReader(
            transport,
            object : dev.acme.adbtoolbox.domain.appdata.AppDatabaseTransfer {
                override suspend fun pull(serial: dev.acme.adbtoolbox.domain.adb.DeviceSerial, access: dev.acme.adbtoolbox.domain.appdata.AppDataAccess, packageName: String, fileName: String) =
                    dev.acme.adbtoolbox.domain.appdata.DatabaseCopy.Failed("catalog")

                override suspend fun push(serial: dev.acme.adbtoolbox.domain.adb.DeviceSerial, access: dev.acme.adbtoolbox.domain.appdata.AppDataAccess, packageName: String, fileName: String, localPath: String): String? = null
            },
            object : dev.acme.adbtoolbox.domain.appdata.SqliteEngine {
                override suspend fun open(localPath: String) = error("catalog")
            },
        ),
        confirmation = { _, _, _, _ -> false },
    )
}
