package dev.acme.adbtoolbox.domain.device

/**
 * Whether a device serial identifies a USB-attached device or a wireless (`ip:port`) connection
 * (ADR 0005/adb-development: "support both USB and wireless ADB serials ... not just USB-style").
 */
enum class DeviceConnectionKind {
    Usb,
    Wifi,

    /** A local emulator (`emulator-<console port>`), shown as "Emulator" rather than USB. */
    Emulator,
    ;

    companion object {
        private val WIRELESS_SERIAL = Regex("""^[^\s:]+:\d+$""")
        private val EMULATOR_SERIAL = Regex("""^emulator-\d+$""")

        fun of(serial: String): DeviceConnectionKind = when {
            WIRELESS_SERIAL.matches(serial) -> Wifi
            EMULATOR_SERIAL.matches(serial) -> Emulator
            else -> Usb
        }
    }
}
