package dev.acme.adbtoolbox.adapters.adb.discovery

import dev.acme.adbtoolbox.domain.discovery.AndroidSdkPlatformToolsSource
import dev.acme.adbtoolbox.domain.discovery.ConfiguredToolPathSource
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.ExecutableFileProbe
import dev.acme.adbtoolbox.domain.discovery.HostPlatformProvider
import dev.acme.adbtoolbox.domain.discovery.PathEnvironmentSource
import dev.acme.adbtoolbox.domain.discovery.ToolDescriptors
import dev.acme.adbtoolbox.domain.discovery.ToolDiscoveryCache
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.executableFileName
import dev.acme.adbtoolbox.domain.discovery.joinPath
import dev.acme.adbtoolbox.domain.process.ProcessCommand
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import dev.acme.adbtoolbox.domain.process.executeBuffered
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The [ToolLocator] implementation (ADR 0001): resolves a tool via the lookup order fixed by
 * design/IMPLEMENTATION.md §4 — [configuredPathSource] first, then each of [androidSdkSources] in
 * order, then [pathEnvironmentSource] — validating every candidate with [executableProbe] and
 * confirming its version by running it through [processExecutor] (task 002's centralized executor,
 * never a raw process spawn). A misconfigured [configuredPathSource] entry fails immediately with
 * [DiscoveryError.ExecutableInvalid] rather than silently falling through to a lower tier, since
 * that would mask a real misconfiguration the user explicitly set.
 *
 * [androidSdkSources] is a list because more than one adapter can know where the Android SDK lives
 * (an environment-variable-based `:adapters-jvm` source today, an IDE-SDK-based `:intellij` source
 * once task 007 wires composition) — each is tried in the order given.
 */
class DefaultToolLocator(
    private val configuredPathSource: ConfiguredToolPathSource,
    private val androidSdkSources: List<AndroidSdkPlatformToolsSource>,
    private val pathEnvironmentSource: PathEnvironmentSource,
    private val executableProbe: ExecutableFileProbe,
    private val hostPlatformProvider: HostPlatformProvider,
    private val processExecutor: ProcessExecutor,
    private val cache: ToolDiscoveryCache = ToolDiscoveryCache(),
    private val versionQueryTimeout: Duration = 5.seconds,
) : ToolLocator {

    override suspend fun locate(toolId: ToolId, forceRefresh: Boolean): DiscoveryOutcome {
        if (!forceRefresh) {
            cache.get(toolId)?.let { return DiscoveryOutcome.Found(it) }
        }

        val os = hostPlatformProvider.current()
        val fileName = executableFileName(toolId, os)

        val configured = configuredPathSource.configuredPath(toolId)
        if (configured != null) {
            if (!executableProbe.isExecutable(configured)) {
                return DiscoveryOutcome.Failed(
                    DiscoveryError.ExecutableInvalid(
                        toolId = toolId,
                        source = ToolSource.ConfiguredPath,
                        path = configured,
                        reason = "Configured path does not point to an executable file",
                    ),
                )
            }
            return resolveVersion(toolId, ToolSource.ConfiguredPath, configured)
        }

        var sawSdkDirectory = false
        for (sdkSource in androidSdkSources) {
            val directory = sdkSource.platformToolsDirectory() ?: continue
            sawSdkDirectory = true
            val candidate = joinPath(directory, fileName, os)
            if (executableProbe.isExecutable(candidate)) {
                return resolveVersion(toolId, ToolSource.AndroidSdk, candidate)
            }
        }

        val pathDirectories = pathEnvironmentSource.directories()
        for (directory in pathDirectories) {
            val candidate = joinPath(directory, fileName, os)
            if (executableProbe.isExecutable(candidate)) {
                return resolveVersion(toolId, ToolSource.PathFallback, candidate)
            }
        }

        val attempted = buildList {
            if (sawSdkDirectory) add(ToolSource.AndroidSdk)
            if (pathDirectories.isNotEmpty()) add(ToolSource.PathFallback)
        }
        return DiscoveryOutcome.Failed(DiscoveryError.ToolNotFound(toolId, attempted))
    }

    override suspend fun invalidate(toolId: ToolId) {
        cache.invalidate(toolId)
    }

    private suspend fun resolveVersion(toolId: ToolId, source: ToolSource, path: String): DiscoveryOutcome {
        val descriptor = ToolDescriptors.of(toolId)
        val result = processExecutor.executeBuffered(
            ProcessRequest(
                command = ProcessCommand(executable = path, arguments = descriptor.versionQueryArguments),
                timeout = versionQueryTimeout,
            ),
        )

        val outcome = result.outcome
        if (outcome !is ProcessOutcome.Completed || outcome.exitCode != 0) {
            return DiscoveryOutcome.Failed(
                DiscoveryError.VersionQueryFailed(
                    toolId = toolId,
                    source = source,
                    path = path,
                    reason = describeProcessFailure(outcome, result.stderr),
                ),
            )
        }

        val version = descriptor.parseVersion(result.stdout) ?: descriptor.parseVersion(result.stderr)
        if (version == null) {
            return DiscoveryOutcome.Failed(
                DiscoveryError.VersionQueryFailed(
                    toolId = toolId,
                    source = source,
                    path = path,
                    reason = "Could not parse a version from the tool's output",
                ),
            )
        }

        val tool = DiscoveredTool(toolId, source, ToolExecutablePath.of(path), version)
        cache.put(toolId, tool)
        return DiscoveryOutcome.Found(tool)
    }

    private fun describeProcessFailure(outcome: ProcessOutcome, stderr: String): String = when (outcome) {
        is ProcessOutcome.Completed -> "exited with code ${outcome.exitCode}: ${stderr.ifBlank { "no error output" }}"
        ProcessOutcome.TimedOut -> "version query timed out"
        is ProcessOutcome.StartFailure -> "failed to start: ${outcome.reason}"
    }
}
