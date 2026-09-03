package dev.acme.adbtoolbox.domain.device

/**
 * The connection state of one ADB-visible device, as reported by `adb devices -l`'s state column.
 * [Unknown] preserves the raw state token instead of throwing, so a state this project does not yet
 * recognize degrades to a visible-but-unrecognized row rather than crashing discovery
 * (adb-development: "handle ... malformed/unexpected output without crashing").
 */
sealed interface DeviceConnectionState {
    /** `device` — online and ready to accept commands. */
    data object Online : DeviceConnectionState

    /** `offline` — connected but not responding (e.g. mid-boot, bridge desync). */
    data object Offline : DeviceConnectionState

    /** `unauthorized` — connected but the "Allow USB debugging" prompt has not been accepted. */
    data object Unauthorized : DeviceConnectionState

    /** `no permissions ...` — the host lacks OS-level permission to open the device (Linux udev rules). */
    data object NoPermissions : DeviceConnectionState

    /** `authorizing` — mid-handshake, waiting on the device to prompt or the user to respond. */
    data object Authorizing : DeviceConnectionState

    /** A state token `adb devices -l` printed that this project does not recognize. */
    data class Unknown(val raw: String) : DeviceConnectionState
}
