package io.github.dkej123.devicecockpit.domain.display.developer

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbTextResult
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.display.DisplaySettingRead
import io.github.dkej123.devicecockpit.domain.display.looksLikePermissionDenial
import io.github.dkej123.devicecockpit.domain.display.reachedDevice

/** Developer options' "Background process limit" values: [STANDARD] clears the override. */
object BackgroundProcessLimit {
    const val STANDARD = -1
    val PRESETS: List<Int> = listOf(STANDARD, 0, 1, 2, 3, 4)

    /** The wording Android's own Developer options use. */
    fun label(limit: Int): String = when {
        limit < 0 -> "Standard limit"
        limit == 0 -> "No background processes"
        limit == 1 -> "At most 1 process"
        else -> "At most $limit processes"
    }
}

/** What the on-device dev-options helper is asked to do; every action reports the process limit afterwards. */
sealed interface DevOptionsAction {
    data object Read : DevOptionsAction

    data class SetProcessLimit(val limit: Int) : DevOptionsAction

    data class SetAlwaysFinish(val enabled: Boolean) : DevOptionsAction
}

/**
 * Runs [DevOptionsAction]s on a device. The two settings it covers exist only inside the running
 * activity manager (`setProcessLimit`, `setAlwaysFinish`), whose binder codes differ per Android
 * release, so they go through the pushed device helper (ADR 0012) rather than `service call`.
 */
interface ActivityManagerDebugPort {
    /** The background process limit after [action], [BackgroundProcessLimit.STANDARD] when not overridden. */
    suspend fun run(serial: DeviceSerial, action: DevOptionsAction): DisplaySettingRead<Int>
}

private const val HEADER = "ADBTOOLBOX-DEVOPTIONS 1"
private const val MAIN_CLASS = "io.github.dkej123.devicecockpit.devicehelper.DevOptionsMain"
private const val LIMIT_PREFIX = "process_limit="
private const val ERROR_PREFIX = "error="

/**
 * Command/parser for the helper's `DevOptionsMain` entry point: it prints [HEADER], then either
 * `process_limit=<n>` or `error=<exception>`, then `END`.
 */
object DevOptionsHelperCommand {

    /** [remotePath] is the pushed helper jar (`AppInfoCommand.remotePath`), safe as a literal `CLASSPATH=`. */
    fun request(serial: DeviceSerial, remotePath: String, action: DevOptionsAction): AdbDeviceRequest {
        val arguments = when (action) {
            DevOptionsAction.Read -> listOf("get")
            is DevOptionsAction.SetProcessLimit -> listOf("process-limit", action.limit.toString())
            is DevOptionsAction.SetAlwaysFinish -> listOf("always-finish", if (action.enabled) "1" else "0")
        }
        return shell(serial, "CLASSPATH=$remotePath", "app_process", "/", MAIN_CLASS, *arguments.toTypedArray())
    }

    fun parse(result: AdbTextResult): DisplaySettingRead<Int> {
        if (!result.outcome.reachedDevice()) return DisplaySettingRead.TransportFailed(result.outcome)
        val lines = result.stdout.lines().map { it.trim() }
        if (HEADER !in lines) return DisplaySettingRead.Malformed(raw = result.stdout + result.stderr, reason = "helper did not start")
        lines.firstOrNull { it.startsWith(ERROR_PREFIX) }?.removePrefix(ERROR_PREFIX)?.let { error ->
            return if (error.looksLikePermissionDenial()) {
                DisplaySettingRead.PermissionDenied(error)
            } else {
                DisplaySettingRead.Malformed(raw = result.stdout, reason = error)
            }
        }
        val limit = lines.firstOrNull { it.startsWith(LIMIT_PREFIX) }?.removePrefix(LIMIT_PREFIX)?.toIntOrNull()
            ?: return DisplaySettingRead.Malformed(raw = result.stdout, reason = "no process limit reported")
        return DisplaySettingRead.Value(limit)
    }
}
