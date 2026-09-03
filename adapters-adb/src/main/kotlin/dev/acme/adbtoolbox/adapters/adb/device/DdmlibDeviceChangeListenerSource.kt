package dev.acme.adbtoolbox.adapters.adb.device

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps ddmlib's static [AndroidDebugBridge.IDeviceChangeListener] hotplug notifications as a cold
 * [Flow] of "the device list may have changed" pulses — never the [IDevice] payload itself, since
 * [AdbDeviceRepository] always re-derives the authoritative device list from a fresh
 * `adb devices -l` parse (task 008: one source of truth for device fields, ddmlib events are only a
 * refresh trigger). Registration is static/global (ddmlib does not scope this listener per
 * bridge/project), so this class needs no `com.intellij.openapi.project.Project` and stays entirely
 * in `:adapters-adb`, unlike [DdmlibDeviceSource]'s IDE-side implementation.
 *
 * The listener is added on first collection and removed via [awaitClose] when collection is
 * cancelled — never left registered after the owning scope is torn down (task 008: "cancel its
 * listeners ... on disposal").
 */
class DdmlibDeviceChangeListenerSource {

    fun events(): Flow<Unit> = callbackFlow {
        val listener = object : AndroidDebugBridge.IDeviceChangeListener {
            override fun deviceConnected(device: IDevice) {
                trySend(Unit)
            }

            override fun deviceDisconnected(device: IDevice) {
                trySend(Unit)
            }

            override fun deviceChanged(device: IDevice, changeMask: Int) {
                trySend(Unit)
            }
        }
        AndroidDebugBridge.addDeviceChangeListener(listener)
        awaitClose { AndroidDebugBridge.removeDeviceChangeListener(listener) }
    }
}
