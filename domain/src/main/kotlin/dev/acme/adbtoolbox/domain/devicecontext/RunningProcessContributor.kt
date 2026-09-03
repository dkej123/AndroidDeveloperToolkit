package dev.acme.adbtoolbox.domain.devicecontext

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/** One cross-feature-visible running-process signal (e.g. "scrcpy is running", "REC 00:42"). */
data class RunningProcessInfo(val id: String, val description: String)

/**
 * A feature-local contribution port (task 014): a feature owning a long-running device session
 * (scrcpy mirroring, screen recording, ...) implements this against its own session state and
 * registers it with `DeviceContextAggregator`, so e.g. the Device view can show "recording..." even
 * though Capture owns that feature — without either feature editing a shared manager.
 */
interface RunningProcessContributor {

    /** The processes this contributor currently has running for [serial] (`null` when no device is selected). */
    fun runningProcessesFor(serial: DeviceSerial?): List<RunningProcessInfo>
}
