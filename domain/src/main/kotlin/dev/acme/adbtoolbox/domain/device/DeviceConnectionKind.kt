package dev.acme.adbtoolbox.domain.device

/**
 * Whether a device serial identifies a USB-attached device or a wireless (`ip:port`) connection
 * (ADR 0005/adb-development: "support both USB and wireless ADB serials ... not just USB-style").
 */
enum class DeviceConnectionKind {
    Usb,
    Wifi,
    ;

    companion object {
        private val WIRELESS_SERIAL = Regex("""^[^\s:]+:\d+$""")

        fun of(serial: String): DeviceConnectionKind =
            if (WIRELESS_SERIAL.matches(serial)) Wifi else Usb
    }
}
