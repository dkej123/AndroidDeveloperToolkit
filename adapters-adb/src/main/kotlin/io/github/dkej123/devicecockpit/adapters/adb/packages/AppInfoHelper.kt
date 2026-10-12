package io.github.dkej123.devicecockpit.adapters.adb.packages

import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbStreamEvent
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.packages.AppInfo
import io.github.dkej123.devicecockpit.domain.packages.AppInfoCommand
import io.github.dkej123.devicecockpit.domain.packages.AppInfoLookup
import io.github.dkej123.devicecockpit.domain.packages.AppInfoLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.security.MessageDigest

private const val HELPER_RESOURCE = "/io/github/dkej123/devicecockpit/adapters/adb/apps/app-info-helper.jar"
// Big enough for the largest tile (28 pt Current app / app details) at 2x HiDPI; ~1 MB for 200 apps.
private const val DEFAULT_ICON_SIZE_PX = 64

// Beyond this many names the command line gets long enough to hit old devices' shell limits, and
// the helper is about as fast listing every installed app as it is listing a subset.
private const val MAX_PACKAGE_ARGUMENTS = 40

// One clean run plus a few reruns that each skip the icon the previous run died on.
private const val MAX_RUNS = 4

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
                    Files.createTempFile("device-cockpit-app-info-", ".jar").toFile().apply {
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
 * Pushes the helper jar (ADR 0010) to each device once per session — skipped when a previous session
 * already left the same version there — and deletes the versions older builds pushed. Shared by every helper entry point ([AppInfoHelper],
 * [DevOptionsHelper]) so they never push the same file concurrently.
 */
class DeviceHelperDeployment(
    private val transport: AdbTransport,
    private val bundle: AppInfoHelperBundle = AppInfoHelperBundle.fromClasspath(),
) {
    private val deployed = mutableSetOf<DeviceSerial>()
    private val lock = Mutex()

    /** The helper's path on [serial], or `null` when it could not be deployed. */
    suspend fun ensureDeployed(serial: DeviceSerial): String? = lock.withLock {
        val remotePath = AppInfoCommand.remotePath(bundle.version)
        if (serial in deployed) return@withLock remotePath
        val present = AppInfoCommand.isPresent(transport.executeText(AppInfoCommand.presenceRequest(serial, remotePath)))
        if (!present) {
            val localPath = bundle.localPath() ?: return@withLock null
            val outcome = transport.executeText(AppInfoCommand.pushRequest(serial, localPath, remotePath)).outcome
            if (outcome !is AdbOutcome.Completed || (outcome.exitCode != null && outcome.exitCode != 0)) {
                return@withLock null
            }
            // Best-effort: a failed cleanup only leaves a few KB behind.
            transport.executeText(AppInfoCommand.staleCleanupRequest(serial, remotePath))
        }
        deployed += serial
        remotePath
    }

    /** The pushed file may have been removed or be unusable; check it again next time. */
    suspend fun forget(serial: DeviceSerial) {
        lock.withLock { deployed -= serial }
    }
}

/**
 * Runs the on-device app-info helper (ADR 0007) through [transport], deployed by [deployment], and
 * delivers its records to the caller as they stream in.
 */
class AppInfoHelper(
    private val transport: AdbTransport,
    bundle: AppInfoHelperBundle = AppInfoHelperBundle.fromClasspath(),
    private val iconSizePx: Int = DEFAULT_ICON_SIZE_PX,
    private val deployment: DeviceHelperDeployment = DeviceHelperDeployment(transport, bundle),
) : AppInfoLookup {
    override suspend fun lookup(serial: DeviceSerial, packageName: String): AppInfo? {
        var found: AppInfo? = null
        query(serial, listOf(packageName)) { if (it.packageName == packageName) found = it }
        return found
    }

    /**
     * Reports [AppInfo] for [packages] (possibly for more, when the list is long enough that every
     * installed app is requested instead). Returns `false` when the helper could not be deployed or
     * did not start; the caller then keeps its own fallback. A run that starts but is cut short
     * still returns `true` — whatever was reported stays valid.
     *
     * Rendering some vendor icons can abort the helper natively, which no catch on the device
     * survives. The helper names each package before describing it, so a run that dies is repeated
     * for what is still missing, with the package it died on reported without an icon — at most
     * [MAX_RUNS] times, and only while each death names a new package.
     */
    suspend fun query(serial: DeviceSerial, packages: List<String>, onInfo: suspend (AppInfo) -> Unit): Boolean {
        val remotePath = deployment.ensureDeployed(serial) ?: return false

        val everyApp = packages.size > MAX_PACKAGE_ARGUMENTS
        val reported = mutableSetOf<String>()
        val skipIcons = linkedSetOf<String>()
        repeat(MAX_RUNS) { run ->
            val pending = packages.filterNot(reported::contains)
            if (!everyApp && pending.isEmpty()) return true
            val result = runOnce(serial, remotePath, if (everyApp) emptyList() else pending, skipIcons) { info ->
                if (reported.add(info.packageName)) onInfo(info)
            }
            if (!result.started) {
                if (run == 0) {
                    deployment.forget(serial)
                    return false
                }
                return true
            }
            if (result.finished) return true
            val suspects = result.inFlight - skipIcons
            if (suspects.isEmpty()) return true
            skipIcons += suspects
        }
        return true
    }

    private class RunResult(val started: Boolean, val finished: Boolean, val inFlight: Set<String>)

    private suspend fun runOnce(
        serial: DeviceSerial,
        remotePath: String,
        arguments: List<String>,
        skipIcons: Set<String>,
        onInfo: suspend (AppInfo) -> Unit,
    ): RunResult {
        var started = false
        var finished = false
        // Started but not yet reported: the helper describes a few packages at once.
        val inFlight = mutableSetOf<String>()
        transport.executeStream(AppInfoCommand.request(serial, remotePath, iconSizePx, arguments, skipIcons)).collect { event ->
            if (event !is AdbStreamEvent.Line) return@collect
            when (val line = AppInfoCommand.parseLine(event.text)) {
                AppInfoLine.Header -> started = true
                AppInfoLine.End -> finished = started
                is AppInfoLine.Started -> if (started) inFlight += line.packageName
                is AppInfoLine.Missing -> inFlight -= line.packageName
                is AppInfoLine.Found -> if (started) {
                    inFlight -= line.info.packageName
                    onInfo(line.info)
                }
                is AppInfoLine.Malformed -> Unit
            }
        }
        return RunResult(started, finished, inFlight)
    }
}
