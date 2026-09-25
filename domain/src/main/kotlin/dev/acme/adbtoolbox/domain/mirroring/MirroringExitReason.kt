package dev.acme.adbtoolbox.domain.mirroring

/** Why a [MirroringSessionState.Running]/[MirroringSessionState.Starting] session ended. */
sealed interface MirroringExitReason {

    /** The caller called `stop()` — a normal, requested teardown. */
    data object Requested : MirroringExitReason

    /** The scrcpy process exited on its own with a zero exit code — the user closed the mirroring
     * window on their desktop, distinct from a requested [Requested] stop. */
    data object ExternalWindowExit : MirroringExitReason

    /** The scrcpy process exited on its own with a non-zero exit code (covers, among other causes,
     * the device disconnecting while mirroring). [detail] is scrcpy's first `ERROR:` message, when
     * it printed one — the root cause, e.g. "Could not find any ADB device". */
    data class ProcessExited(val exitCode: Int, val detail: String? = null) : MirroringExitReason

    /** The process was killed after exceeding its [dev.acme.adbtoolbox.domain.process.ProcessRequest.timeout]. */
    data object Timeout : MirroringExitReason

    /** The owning coroutine scope was cancelled (e.g. plugin/project disposal) rather than an
     * explicit `stop()` call. */
    data object Cancelled : MirroringExitReason
}
