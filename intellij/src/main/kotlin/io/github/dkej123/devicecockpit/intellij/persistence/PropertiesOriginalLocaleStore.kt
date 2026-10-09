package io.github.dkej123.devicecockpit.intellij.persistence

import com.intellij.ide.util.PropertiesComponent
import io.github.dkej123.devicecockpit.application.locale.OriginalLocaleStore
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial

/**
 * The locale each device had when ADB Toolbox first saw it (design §3b "Original"), kept per serial
 * across IDE restarts in the application-level [PropertiesComponent] — a device's original locale
 * does not depend on the project it is used from.
 */
class PropertiesOriginalLocaleStore(private val properties: () -> PropertiesComponent = PropertiesComponent::getInstance) : OriginalLocaleStore {
    override fun get(serial: DeviceSerial): String? = properties().getValue(key(serial))

    override fun put(serial: DeviceSerial, locale: String?) {
        properties().setValue(key(serial), locale)
    }

    private fun key(serial: DeviceSerial) = "adbtoolbox.originalLocale.$serial"
}
