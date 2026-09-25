package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.packages.AppInfo
import dev.acme.adbtoolbox.domain.packages.AppInfoCommand
import dev.acme.adbtoolbox.domain.packages.AppInfoLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.security.MessageDigest

private const val HELPER_RESOURCE = "/dev/acme/adbtoolbox/adapters/adb/apps/app-info-helper.jar"
private const val DEFAULT_ICON_SIZE_PX = 32

// Beyond this many names the command line gets long enough to hit old devices' shell limits, and
// the helper is about as fast listing every installed app as it is listing a subset.
private const val MAX_PACKAGE_ARGUMENTS = 40

/**
 * The dexed helper jar built from `src/deviceHelper` (ADR 0007). [version] names the pushed file,
 * so a rebuilt helper is pushed again instead of reusing a stale copy. [localPath] returns a host
 * file `adb push` can read, or `null` when the jar cannot be materialized.
 */
class AppInfoHelperBundle(val version: String, val localPath: () -> String?) {
    companion object {
        fun fromClasspath(): AppInfoHelperBundle {
            val bytes = AppInfoHelperBundle::class.java.getResourceAsStream(HELPER_RESOURCE)?.use { it.readBytes() }
                ?: return AppInfoHelperBundle("missing") { null }
            val version = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val file by lazy {
                runCatching {
                    Files.createTempFile("adb-toolbox-app-info-", ".jar").toFile().apply {
                        deleteOnExit()
                        writeBytes(bytes)
                    }.absolutePath
                }.getOrNull()
            }
            return AppInfoHelperBundle(version) { file }
        }
    }
}

/**
 * Deploys and runs the on-device app-info helper (ADR 0007) through [transport]: the jar is pushed
 * to each device once per session (skipped when a previous session already left the same version
 * there), then run with `app_process`, and its records are delivered to the caller as they stream in.
 */
class AppInfoHelper(
    private val transport: AdbTransport,
    private val bundle: AppInfoHelperBundle = AppInfoHelperBundle.fromClasspath(),
    private val iconSizePx: Int = DEFAULT_ICON_SIZE_PX,
) {
    private val deployed = mutableSetOf<DeviceSerial>()
    private val deployLock = Mutex()

    /**
     * Reports [AppInfo] for [packages] (possibly for more, when the list is long enough that every
     * installed app is requested instead). Returns `false` when the helper could not be deployed or
     * did not start; the caller then keeps its own fallback. A run that starts but is cut short
     * still returns `true` — whatever was reported stays valid.
     */
    suspend fun query(serial: DeviceSerial, packages: List<String>, onInfo: suspend (AppInfo) -> Unit): Boolean {
        val remotePath = AppInfoCommand.remotePath(bundle.version)
        if (!ensureDeployed(serial, remotePath)) return false

        val arguments = if (packages.size > MAX_PACKAGE_ARGUMENTS) emptyList() else packages
        var started = false
        transport.executeStream(AppInfoCommand.request(serial, remotePath, iconSizePx, arguments)).collect { event ->
            if (event !is AdbStreamEvent.Line) return@collect
            when (val line = AppInfoCommand.parseLine(event.text)) {
                AppInfoLine.Header -> started = true
                is AppInfoLine.Found -> if (started) onInfo(line.info)
                else -> Unit
            }
        }
        if (!started) {
            // The pushed file may have been removed or be unusable; check it again next time.
            deployLock.withLock { deployed -= serial }
        }
        return started
    }

    private suspend fun ensureDeployed(serial: DeviceSerial, remotePath: String): Boolean = deployLock.withLock {
        if (serial in deployed) return@withLock true
        val present = AppInfoCommand.isPresent(transport.executeText(AppInfoCommand.presenceRequest(serial, remotePath)))
        if (!present) {
            val localPath = bundle.localPath() ?: return@withLock false
            val outcome = transport.executeText(AppInfoCommand.pushRequest(serial, localPath, remotePath)).outcome
            if (outcome !is AdbOutcome.Completed || (outcome.exitCode != null && outcome.exitCode != 0)) {
                return@withLock false
            }
        }
        deployed += serial
        true
    }
}
