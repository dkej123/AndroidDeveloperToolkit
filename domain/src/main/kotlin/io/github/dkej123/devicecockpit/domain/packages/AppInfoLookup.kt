package io.github.dkej123.devicecockpit.domain.packages

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial

/** Reads one installed app's label and icon from the device (ADR 0010); `null` when it cannot. */
fun interface AppInfoLookup {
    suspend fun lookup(serial: DeviceSerial, packageName: String): AppInfo?
}
