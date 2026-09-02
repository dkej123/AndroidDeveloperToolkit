package dev.acme.adbtoolbox.domain.adb

/**
 * A validated ADB device serial — either a USB-style serial (e.g. `R58N90ABCDE`) or a wireless
 * `ip:port` serial (e.g. `192.168.1.42:5555`). The only way to obtain one is [DeviceSerial.of],
 * which rejects a blank value, so every device-scoped operation that requires a [DeviceSerial] is
 * serial-scoped by construction rather than by caller discipline.
 */
@JvmInline
value class DeviceSerial private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        fun of(value: String): DeviceSerial {
            require(value.isNotBlank()) { "Device serial must not be blank" }
            return DeviceSerial(value)
        }
    }
}
