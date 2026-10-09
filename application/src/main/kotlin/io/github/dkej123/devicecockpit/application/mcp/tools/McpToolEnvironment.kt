package io.github.dkej123.devicecockpit.application.mcp.tools

import io.github.dkej123.devicecockpit.application.appdetails.AppDataReader
import io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase
import io.github.dkej123.devicecockpit.application.apps.ClearDataUseCase
import io.github.dkej123.devicecockpit.application.apps.UninstallUseCase
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppUseCase
import io.github.dkej123.devicecockpit.application.layout.CaptureLayoutUseCase
import io.github.dkej123.devicecockpit.application.layout.LayoutCapture
import io.github.dkej123.devicecockpit.application.locale.DeviceLocaleUseCase
import io.github.dkej123.devicecockpit.application.locale.EmulatorLocationUseCase
import io.github.dkej123.devicecockpit.application.mcp.McpArgumentException
import io.github.dkej123.devicecockpit.application.mcp.optionalString
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.capture.ImageScaler
import io.github.dkej123.devicecockpit.domain.capture.MarkBox
import io.github.dkej123.devicecockpit.domain.capture.ScreenMarker
import io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState
import io.github.dkej123.devicecockpit.domain.device.DeviceRepository
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.selectedSerialOrNull
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import io.github.dkej123.devicecockpit.domain.layout.UiHierarchy
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
    val marker: ScreenMarker,
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
            ?: throw McpToolFailure("No device is selected in Device Cockpit. Call list_devices, then pass serial.")
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

    /** Pixel-found shapes of the last annotated screenshot per device, by mark number (dp). */
    private val lastMarks = mutableMapOf<DeviceSerial, Map<Int, MarkBox>>()

    fun rememberMarks(serial: DeviceSerial, marks: Map<Int, MarkBox>) {
        lastMarks[serial] = marks
    }

    fun mark(serial: DeviceSerial, number: Int): MarkBox? = lastMarks[serial]?.get(number)

    /** The `package` argument, else the app in front. */
    suspend fun packageOrForeground(serial: DeviceSerial, arguments: JsonObject): String =
        arguments.optionalString("package") ?: when (val front = currentApp.read(serial).foreground) {
            is ForegroundState.App -> front.packageName
            is ForegroundState.SystemUi -> front.behind?.packageName ?: throw McpToolFailure("System UI is in front; pass package.")
            else -> throw McpArgumentException("No app is in front; pass package.")
        }
}
