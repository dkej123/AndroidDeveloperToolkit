package io.github.dkej123.devicecockpit.application.locale

import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.locale.DeviceLocalePort
import io.github.dkej123.devicecockpit.domain.locale.LocaleAction
import io.github.dkej123.devicecockpit.domain.locale.LocaleRead
import io.github.dkej123.devicecockpit.domain.locale.LocaleTag
import io.github.dkej123.devicecockpit.domain.location.EmulatorLocationCommand
import io.github.dkej123.devicecockpit.domain.location.GeoPoint

/** The device's locale as the Language & region section shows it (design §3b). */
data class DeviceLocaleState(val current: String, val original: String) {
    /** An override counts in the status-bar chip and Reset all. */
    val overridden: Boolean get() = !current.equals(original, ignoreCase = true)
}

sealed interface LocaleResult {
    data class Applied(val state: DeviceLocaleState) : LocaleResult

    data class Failed(val reason: String) : LocaleResult
}

/** Where the locale a device had when ADB Toolbox first saw it is kept, per serial (persisted by the IDE layer). */
interface OriginalLocaleStore {
    fun get(serial: DeviceSerial): String?

    fun put(serial: DeviceSerial, locale: String?)
}

class InMemoryOriginalLocaleStore : OriginalLocaleStore {
    private val originals = mutableMapOf<DeviceSerial, String>()

    override fun get(serial: DeviceSerial): String? = originals[serial]

    override fun put(serial: DeviceSerial, locale: String?) {
        if (locale == null) originals.remove(serial) else originals[serial] = locale
    }
}

/**
 * Reads, sets and resets the device language (task 060). The first locale read for a serial is its
 * "original", kept until a reset brings the device back to it.
 */
class DeviceLocaleUseCase(private val port: DeviceLocalePort, private val originals: OriginalLocaleStore) {

    suspend fun read(serial: DeviceSerial): LocaleResult = applied(serial, port.run(serial, LocaleAction.Read))

    suspend fun set(serial: DeviceSerial, tag: LocaleTag): LocaleResult {
        if (originals.get(serial) == null) {
            (read(serial) as? LocaleResult.Failed)?.let { return it }
        }
        return applied(serial, port.run(serial, LocaleAction.Set(listOf(tag))))
    }

    suspend fun reset(serial: DeviceSerial): LocaleResult {
        val original = originals.get(serial)?.let(LocaleTag::of) ?: return read(serial)
        return applied(serial, port.run(serial, LocaleAction.Set(listOf(original))))
    }

    private fun applied(serial: DeviceSerial, read: LocaleRead): LocaleResult = when (read) {
        is LocaleRead.Failed -> LocaleResult.Failed(read.reason)
        is LocaleRead.Locales -> {
            val current = read.tags.firstOrNull() ?: return LocaleResult.Failed("The device reported no locale")
            val original = originals.get(serial) ?: current.also { originals.put(serial, it) }
            LocaleResult.Applied(DeviceLocaleState(current, original))
        }
    }
}

sealed interface LocationResult {
    data class Set(val point: GeoPoint) : LocationResult

    /** Physical devices and emulators reached over TCP have no console (design §3c). */
    data object NotAnEmulator : LocationResult

    data class Failed(val reason: String) : LocationResult
}

/** Sends a GPS fix to an emulator (task 060). */
class EmulatorLocationUseCase(private val transport: AdbTransport) {

    suspend fun set(serial: DeviceSerial, point: GeoPoint): LocationResult {
        if (!EmulatorLocationCommand.isSupported(serial)) return LocationResult.NotAnEmulator
        val result = transport.executeText(EmulatorLocationCommand.request(serial, point))
        return if (EmulatorLocationCommand.isAccepted(result)) {
            LocationResult.Set(point)
        } else {
            LocationResult.Failed((result.stdout + result.stderr).trim().ifEmpty { "The emulator console did not answer" })
        }
    }
}
