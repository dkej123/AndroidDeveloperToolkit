package dev.acme.adbtoolbox.domain.adb

import kotlin.time.Duration

/**
 * A request the [AdbTransport] gateway can execute. [AdbServerRequest] targets the ADB server/host
 * itself (`devices -l`, `pair`, `connect`) and carries no serial. [AdbDeviceRequest] targets one
 * device and requires a [DeviceSerial] — obtainable only via [DeviceSerial.of] — so a device
 * operation can never be constructed without a nonblank serial.
 *
 * [timeout] follows ADR 0005: a short default for request/response calls, `null` (caller-owned
 * cancellation only) for streams such as logcat.
 */
sealed interface AdbRequest {
    val timeout: Duration?
}

data class AdbServerRequest(
    val arguments: List<String>,
    override val timeout: Duration? = null,
) : AdbRequest

data class AdbDeviceRequest(
    val serial: DeviceSerial,
    val operation: AdbOperation,
    override val timeout: Duration? = null,
) : AdbRequest
