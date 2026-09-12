package dev.acme.adbtoolbox.domain.apps

/**
 * What a task-023 single-step app-lifecycle action (Force-stop or Launch) produced. Mirrors
 * [dev.acme.adbtoolbox.domain.deviceactions.DeviceActionResult]'s shape: every failure path
 * (non-zero/unknown exit status, timeout, cancellation, transport/disconnect failure, unsupported
 * transport call, missing-launcher) is preserved as a caller-readable [Failure.reason] rather than
 * collapsed into a boolean. [RejectedDuplicate] is distinct from [Failure] — it means the action
 * never ran at all because another action for the exact same package+serial
 * (see [dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase]) was already in flight, so a
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
sealed interface AppRestartResult {
    data object Success : AppRestartResult
    data class ForceStopFailed(val reason: String) : AppRestartResult
    data class LaunchFailedAfterForceStop(val reason: String) : AppRestartResult
    data object RejectedDuplicate : AppRestartResult
}
