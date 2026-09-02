package dev.acme.adbtoolbox.adapters.adb.ddmlib

import com.android.ddmlib.IDevice

/**
 * The ddmlib device list [DdmlibAdbTransport] looks a [dev.acme.adbtoolbox.domain.adb.DeviceSerial]
 * up against. Implemented at the `:intellij` composition root (task 007) over the shared
 * `AndroidDebugBridge`/`AdbLibService` instance the Android plugin exposes (ADR 0005) — this module
 * never touches that instance directly, only the plain ddmlib [IDevice] values it returns, so
 * `:adapters-adb` stays free of IntelliJ platform APIs. Trivially faked in tests without a ddmlib
 * bridge or real hardware.
 */
fun interface DdmlibDeviceSource {
    fun devices(): List<IDevice>
}
