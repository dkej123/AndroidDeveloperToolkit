package dev.acme.adbtoolbox.intellij.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.adapters.adb.ddmlib.DdmlibDeviceSource
import org.jetbrains.android.sdk.AndroidSdkUtils

/**
 * The real [DdmlibDeviceSource] the `:intellij` composition root wires into [DdmlibAdbTransport]
 * (ADR 0005) when the Android plugin is present: [AndroidSdkUtils.getDebugBridge] returns the IDE's
 * own shared `AndroidDebugBridge` connection (the one the Android plugin itself establishes and
 * keeps alive) rather than starting a second, duplicate `adb` daemon handshake — exactly the
 * sharing [DdmlibAdbTransport]'s documentation describes. Only ever constructed when the Android
 * plugin is present (task 007's composition root), so the `com.android.ddmlib.*` types it touches
 * always resolve to the single copy that plugin provides via IntelliJ's plugin classloader
 * delegation (see the `compileOnly(libs.ddmlib)` comments in the adjacent build files).
 *
 * A missing or not-yet-connected bridge is reported as no devices, never a crash — [DdmlibAdbTransport]
 * already turns "no connected device with serial X" into a typed [dev.acme.adbtoolbox.domain.adb.AdbOutcome.TransportFailure].
 */
class IdeAndroidDebugBridgeDeviceSource(private val project: Project) : DdmlibDeviceSource {
    override fun devices(): List<IDevice> {
        val bridge = AndroidSdkUtils.getDebugBridge(project) ?: return emptyList()
        if (!bridge.isConnected) return emptyList()
        return bridge.devices.toList()
    }
}
