package io.github.dkej123.devicecockpit.application.mirroring

import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryError
import io.github.dkej123.devicecockpit.domain.discovery.DiscoveryOutcome
import io.github.dkej123.devicecockpit.domain.discovery.HostPlatformProvider
import io.github.dkej123.devicecockpit.domain.discovery.OperatingSystem
import io.github.dkej123.devicecockpit.domain.discovery.ToolId
import io.github.dkej123.devicecockpit.domain.discovery.ToolLocator
import io.github.dkej123.devicecockpit.domain.discovery.ToolSource
import io.github.dkej123.devicecockpit.domain.discovery.ToolVersion
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether mirroring can start at all: scrcpy is an external tool the user may not have installed. */
sealed interface ScrcpyAvailability {
    data object Checking : ScrcpyAvailability

    data class Available(val version: ToolVersion) : ScrcpyAvailability

    /**
     * scrcpy could not be used: [reason] says why, [installCommand] is this OS's usual way to install
     * it, and [configuredPathInvalid] is true when the path set in Settings is the problem.
     */
    data class Missing(
        val reason: String,
        val installCommand: String,
        val configuredPathInvalid: Boolean,
    ) : ScrcpyAvailability {
        /** [reason] plus what to do about it — the one line shown wherever mirroring can't start. */
        fun fixHint(): String = if (configuredPathInvalid) {
            "$reason Fix or clear it in Settings."
        } else {
            "$reason Install it ($installCommand) or set its path in Settings."
        }
    }
}

/**
 * Resolves scrcpy up front so the Device view can grey out "Start mirroring" and explain how to fix
 * it, instead of failing only after a click (user decision, 2026-09-29). [refresh] re-reads the
 * (cached) lookup — the composition root calls it when the scrcpy path setting changes — and
 * [recheck] drops the cache first, for "Check again" after installing scrcpy.
 */
class ScrcpyAvailabilityViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val toolLocator: ToolLocator,
    private val hostPlatform: HostPlatformProvider,
) {
    private val _state = MutableStateFlow<ScrcpyAvailability>(ScrcpyAvailability.Checking)
    val state: StateFlow<ScrcpyAvailability> = _state.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() = resolve(forceRecheck = false)

    fun recheck() = resolve(forceRecheck = true)

    private fun resolve(forceRecheck: Boolean) {
        job?.cancel()
        if (forceRecheck) _state.value = ScrcpyAvailability.Checking
        job = scope.launch(dispatchers.io) {
            if (forceRecheck) toolLocator.invalidate(ToolId.Scrcpy)
            _state.value = when (val outcome = toolLocator.locate(ToolId.Scrcpy)) {
                is DiscoveryOutcome.Found -> ScrcpyAvailability.Available(outcome.tool.version)
                is DiscoveryOutcome.Failed -> missing(outcome.error)
            }
        }
    }

    private fun missing(error: DiscoveryError): ScrcpyAvailability.Missing {
        val configuredPathInvalid = when (error) {
            is DiscoveryError.ToolNotFound -> false
            is DiscoveryError.ExecutableInvalid -> error.source == ToolSource.ConfiguredPath
            is DiscoveryError.VersionQueryFailed -> error.source == ToolSource.ConfiguredPath
        }
        val reason = when {
            error is DiscoveryError.ToolNotFound -> "scrcpy is not installed, or not on PATH."
            error is DiscoveryError.ExecutableInvalid && configuredPathInvalid ->
                "The scrcpy path set in Settings is not usable (${error.path}: ${error.reason})."
            error is DiscoveryError.ExecutableInvalid -> "scrcpy at ${error.path} is not usable (${error.reason})."
            error is DiscoveryError.VersionQueryFailed -> "scrcpy at ${error.path} could not be run (${error.reason})."
            else -> "scrcpy could not be found."
        }
        return ScrcpyAvailability.Missing(reason, installCommandFor(hostPlatform.current()), configuredPathInvalid)
    }

    private fun installCommandFor(os: OperatingSystem): String = when (os) {
        OperatingSystem.MacOs -> "brew install scrcpy"
        OperatingSystem.Windows -> "winget install --exact Genymobile.scrcpy"
        OperatingSystem.Linux -> "sudo apt install scrcpy"
    }
}
