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

/** Original ordered language tags, comma-separated, per serial (persisted by the IDE layer). Legacy values contain one tag. */
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
        // Always read fresh: the user may have added languages in Android Settings.
        val read = port.run(serial, LocaleAction.Read)
        val state = applied(serial, read)
        if (state is LocaleResult.Failed) return state
        val current = (read as LocaleRead.Locales).tags
        return write(serial, listOf(tag.value) + current.filterNot { it.equals(tag.value, ignoreCase = true) })
    }

    suspend fun reset(serial: DeviceSerial): LocaleResult {
        val saved = originals.get(serial) ?: return read(serial)
        val original = saved.split(',')
        // Restore the original preference order without dropping languages added in the meantime.
        val tags = when (val current = port.run(serial, LocaleAction.Read)) {
            is LocaleRead.Failed -> return LocaleResult.Failed(current.reason)
            is LocaleRead.Locales -> original + current.tags.filterNot { tag -> original.any { it.equals(tag, ignoreCase = true) } }
        }
        return write(serial, tags)
    }

    private suspend fun write(serial: DeviceSerial, tags: List<String>): LocaleResult {
        val locales = tags.map { tag ->
            LocaleTag.of(tag) ?: return LocaleResult.Failed("Cannot preserve device language: $tag")
        }
        return applied(serial, port.run(serial, LocaleAction.Set(locales)))
    }

    private fun applied(serial: DeviceSerial, read: LocaleRead): LocaleResult = when (read) {
        is LocaleRead.Failed -> LocaleResult.Failed(read.reason)
        is LocaleRead.Locales -> {
            val current = read.tags.firstOrNull() ?: return LocaleResult.Failed("The device reported no locale")
            val original = originals.get(serial) ?: read.tags.joinToString(",").also { originals.put(serial, it) }
            LocaleResult.Applied(DeviceLocaleState(current, original.substringBefore(',')))
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
