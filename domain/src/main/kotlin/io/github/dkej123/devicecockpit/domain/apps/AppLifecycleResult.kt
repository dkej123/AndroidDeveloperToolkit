package io.github.dkej123.devicecockpit.domain.apps

/**
 * What a task-023 single-step app-lifecycle action (Force-stop or Launch) produced. Mirrors
 * [io.github.dkej123.devicecockpit.domain.deviceactions.DeviceActionResult]'s shape: every failure path
 * (non-zero/unknown exit status, timeout, cancellation, transport/disconnect failure, unsupported
 * transport call, missing-launcher) is preserved as a caller-readable [Failure.reason] rather than
 * collapsed into a boolean. [RejectedDuplicate] is distinct from [Failure] — it means the action
 * never ran at all because another action for the exact same package+serial
 * (see [io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase]) was already in flight, so a
 * caller can choose to stay silent rather than surface it as an error.
 */
sealed interface AppLifecycleResult {
    data object Success : AppLifecycleResult
    data class Failure(val reason: String) : AppLifecycleResult
    data object RejectedDuplicate : AppLifecycleResult
}

/**
 * What task 023's Restart (force-stop, then launch) produced. Distinct from [AppLifecycleResult]
 * because restart's two steps can fail independently, and the scope's explicit requirement —
 * "restart must NEVER report success if launch fails after force-stop succeeds" — needs its own
 * unambiguous case ([LaunchFailedAfterForceStop]) rather than being folded into a generic
 * [AppLifecycleResult.Failure] that could be misread as "nothing happened".
 */
/**
 * Outcome of a simulated process death: the app was sent home, killed by `am kill` like the system
 * kills a cached process, and brought back so Android recreates its activities from saved state.
 */
sealed interface AppProcessDeathResult {
    data object Success : AppProcessDeathResult

    /** Nothing to kill: the app has no process. */
    data object NotRunning : AppProcessDeathResult

    /** `am kill` only kills background processes; this one survived (a foreground service, or it is still in front). */
    data object StillAlive : AppProcessDeathResult

    data class Failure(val reason: String) : AppProcessDeathResult

    /** The process died but the app did not come back. */
    data class RelaunchFailed(val reason: String) : AppProcessDeathResult

    data object RejectedDuplicate : AppProcessDeathResult
}

sealed interface AppRestartResult {
    data object Success : AppRestartResult
    data class ForceStopFailed(val reason: String) : AppRestartResult
    data class LaunchFailedAfterForceStop(val reason: String) : AppRestartResult
    data object RejectedDuplicate : AppRestartResult
}
