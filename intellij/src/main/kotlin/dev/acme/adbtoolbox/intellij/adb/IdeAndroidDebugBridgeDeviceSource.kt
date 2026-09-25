package dev.acme.adbtoolbox.intellij.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import dev.acme.adbtoolbox.adapters.adb.ddmlib.DdmlibDeviceSource
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import dev.acme.adbtoolbox.domain.diagnostics.NoOpDiagnosticsLog

/**
 * The real [DdmlibDeviceSource] the `:intellij` composition root wires into [DdmlibAdbTransport]
 * (ADR 0005) when the Android plugin is present: [AndroidDebugBridge.getBridge] returns the IDE's
 * own shared `AndroidDebugBridge` connection (the one the Android plugin itself establishes and
 * keeps alive) rather than starting a second, duplicate `adb` daemon handshake — exactly the
 * sharing [DdmlibAdbTransport]'s documentation describes. Only ever constructed when the Android
 * plugin is present (task 007's composition root), so the `com.android.ddmlib.*` types it touches
 * always resolve to the single copy that plugin provides via IntelliJ's plugin classloader
 * delegation (see the `compileOnly(libs.ddmlib)` comments in the adjacent build files).
 *
 * A missing or not-yet-connected bridge is reported as no devices, never a crash — [DdmlibAdbTransport]
 * turns a serial the bridge does not know into [dev.acme.adbtoolbox.domain.adb.AdbOutcome.Unsupported], so the binary transport handles the call.
 */
class IdeAndroidDebugBridgeDeviceSource(
    private val bridgeProvider: () -> AndroidDebugBridge? = AndroidDebugBridge::getBridge,
    private val log: DiagnosticsLog = NoOpDiagnosticsLog,
) : DdmlibDeviceSource {

    @Volatile
    private var lastState: String? = null

    /** The bridge's state as last observed, e.g. `connected devices=1` — shown in diagnostics. */
    fun describeState(): String {
        val bridge = bridgeProvider() ?: return "no bridge (Android plugin has not created one)"
        return when {
            !bridge.isConnected -> "not connected"
            !bridge.hasInitialDeviceList() -> "connected, waiting for the initial device list"
            else -> "connected devices=${bridge.devices.size} " +
                bridge.devices.joinToString(prefix = "[", postfix = "]") { "${it.serialNumber}:${it.state}" }
        }
    }

    /**
     * Only reads the bridge the Android plugin has *already* created. `AndroidSdkUtils.getDebugBridge`
     * is not used: it may initialize the bridge and block on that future indefinitely, which froze
     * every device-scoped action (facts, capture, display, toggles) in a busy state. An absent or
     * not-yet-connected bridge reports no devices, and [DdmlibAdbTransport] then lets the binary
     * transport handle the call.
     */
    override fun devices(): List<IDevice> {
        val bridge = bridgeProvider()
        val devices = if (bridge == null || !bridge.isConnected || !bridge.hasInitialDeviceList()) {
            emptyList()
        } else {
            bridge.devices.toList()
        }
        recordState(bridge, devices)
        return devices
    }

    /** Logs only transitions (null → connected → device count changes), never every lookup. */
    private fun recordState(bridge: AndroidDebugBridge?, devices: List<IDevice>) {
        val state = when {
            bridge == null -> "no bridge"
            !bridge.isConnected -> "not connected"
            !bridge.hasInitialDeviceList() -> "no initial device list"
            else -> "connected devices=${devices.joinToString { it.serialNumber }}"
        }
        if (state != lastState) {
            lastState = state
            log.log(DiagLevel.INFO, DiagCategory.ADB, "ddmlib bridge state", mapOf("state" to state))
        }
    }
}
