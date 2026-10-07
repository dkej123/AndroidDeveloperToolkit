package dev.acme.adbtoolbox.application.mcp.tools

import dev.acme.adbtoolbox.application.appdetails.AppDataReader
import dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase
import dev.acme.adbtoolbox.application.apps.ClearDataUseCase
import dev.acme.adbtoolbox.application.apps.UninstallUseCase
import dev.acme.adbtoolbox.application.currentapp.CurrentAppUseCase
import dev.acme.adbtoolbox.application.layout.CaptureLayoutUseCase
import dev.acme.adbtoolbox.application.layout.LayoutCapture
import dev.acme.adbtoolbox.application.locale.DeviceLocaleUseCase
import dev.acme.adbtoolbox.application.locale.EmulatorLocationUseCase
import dev.acme.adbtoolbox.application.mcp.McpArgumentException
import dev.acme.adbtoolbox.application.mcp.optionalString
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.capture.ImageScaler
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.DeviceRepository
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.selectedSerialOrNull
import dev.acme.adbtoolbox.domain.foreground.ForegroundState
import dev.acme.adbtoolbox.domain.layout.UiHierarchy
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** A destructive app action an agent asked for; the IDE confirms it (ADR 0015, design §11). */
enum class DestructiveAppAction { ClearData, Uninstall }

/** Shows the tool window's confirmation naming the agent; false on Cancel or after 60 s without an answer. */
fun interface McpConfirmation {
    suspend fun confirm(action: DestructiveAppAction, serial: DeviceSerial, packageName: String, agent: String): Boolean
}

/** A failure the agent should read as the tool's answer (not a crash). */
class McpToolFailure(message: String) : Exception(message)

/** Everything the MCP tools use — the same use cases as the tool window (ADR 0015). */
class McpToolEnvironment(
    val transport: AdbTransport,
    val devices: DeviceRepository,
    val selected: StateFlow<SelectedDeviceState>,
    val layout: CaptureLayoutUseCase,
    val imageScaler: ImageScaler,
    val lifecycle: AppLifecycleUseCase,
    val clearData: ClearDataUseCase,
    val uninstall: UninstallUseCase,
    val currentApp: CurrentAppUseCase,
    val locale: DeviceLocaleUseCase,
    val location: EmulatorLocationUseCase,
    val appData: AppDataReader,
    val confirmation: McpConfirmation,
) {
    /** Latest hierarchy per device, so `tap` by ref uses the tree the agent saw in `get_ui`. */
    private val lastHierarchy = mutableMapOf<DeviceSerial, UiHierarchy>()

    /** The `serial` argument, else the device selected in the tool window; it must be online. */
    fun device(arguments: JsonObject): DeviceSerial {
        val requested = arguments.optionalString("serial")?.let(DeviceSerial::of)
        val serial = requested ?: selected.value.selectedSerialOrNull
            ?: throw McpToolFailure("No device is selected in ADB Toolbox. Call list_devices, then pass serial.")
        val device = devices.devices.value.firstOrNull { it.serial == serial }
            ?: throw McpToolFailure("Device $serial is not connected. Call list_devices.")
        if (device.state != DeviceConnectionState.Online) throw McpToolFailure("Device $serial is ${device.state.label()}, not online.")
        return serial
    }

    suspend fun hierarchy(serial: DeviceSerial, fresh: Boolean = true): UiHierarchy {
        if (!fresh) lastHierarchy[serial]?.let { return it }
        return when (val capture = layout.hierarchy(serial)) {
            is LayoutCapture.Captured -> capture.value.also { lastHierarchy[serial] = it }
            is LayoutCapture.Failed -> throw McpToolFailure(capture.reason)
        }
    }

    fun remember(serial: DeviceSerial, hierarchy: UiHierarchy) {
        lastHierarchy[serial] = hierarchy
    }

    fun lastHierarchy(serial: DeviceSerial): UiHierarchy? = lastHierarchy[serial]

    /** The `package` argument, else the app in front. */
    suspend fun packageOrForeground(serial: DeviceSerial, arguments: JsonObject): String =
        arguments.optionalString("package") ?: when (val front = currentApp.read(serial).foreground) {
            is ForegroundState.App -> front.packageName
            is ForegroundState.SystemUi -> front.behind?.packageName ?: throw McpToolFailure("System UI is in front; pass package.")
            else -> throw McpArgumentException("No app is in front; pass package.")
        }
}
