@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.logcat

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.logcat.LogcatPidResolution
import dev.acme.adbtoolbox.domain.logcat.ProcessId
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class PidTrackerTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private class ScriptedPidTransport(private val script: (String) -> AdbTextResult) : AdbTransport {
    val resolvedPackages = mutableListOf<String>()

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command
        val packageName = command.render().substringAfter("'").substringBefore("'")
        resolvedPackages += packageName
        return script(packageName)
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()
    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}

private val SERIAL = DeviceSerial.of("R58N90ABCDE")

/**
 * Covers task 035's scope: reactively resolving the selected package's pid, and re-resolving on
 * demand after an app restart (`design/IMPLEMENTATION.md` §4's "survives restarts by
 * re-resolving") without needing to touch anything else (session, ordinary level/search filters).
 */
class LogcatPackagePidTrackerTest {

    private data class Harness(
        val scope: TestScope,
        val selectedPackage: MutableStateFlow<SelectedPackageState>,
        val transport: ScriptedPidTransport,
        val tracker: LogcatPackagePidTracker,
    )

    private fun harness(
        initialSelection: SelectedPackageState = SelectedPackageState.None,
        script: (String) -> AdbTextResult = { AdbTextResult(AdbOutcome.Completed(0), "1000", "") },
    ): Harness {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val selectedPackage = MutableStateFlow(initialSelection)
        val transport = ScriptedPidTransport(script)
        val resolver = LogcatPidResolver(transport)
        return Harness(
            scope,
            selectedPackage,
            transport,
            LogcatPackagePidTracker(scope, PidTrackerTestDispatchers(dispatcher), selectedPackage, resolver),
        )
    }

    @Test
    fun `no selection means no pid resolution is attempted`() = runTest {
        val h = harness()
        h.scope.runCurrent()

        h.tracker.state.value shouldBe LogcatPidResolution.NotResolved
        h.transport.resolvedPackages shouldBe emptyList()
    }

    @Test
    fun `a selected package is resolved automatically`() = runTest {
        val h = harness(initialSelection = SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.shop")))

        h.scope.runCurrent()

        h.transport.resolvedPackages shouldBe listOf("com.acme.shop")
        h.tracker.state.value shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(1000)))
    }

    @Test
    fun `changing the selected package re-resolves for the new package`() = runTest {
        val h = harness()
        h.scope.runCurrent()

        h.selectedPackage.value = SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.shop"))
        h.scope.runCurrent()

        h.transport.resolvedPackages shouldBe listOf("com.acme.shop")
    }

    @Test
    fun `clearing the selection resets resolution to not-resolved`() = runTest {
        val h = harness(initialSelection = SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.shop")))
        h.scope.runCurrent()

        h.selectedPackage.value = SelectedPackageState.None
        h.scope.runCurrent()

        h.tracker.state.value shouldBe LogcatPidResolution.NotResolved
    }

    @Test
    fun `refresh re-resolves the current selection to pick up a pid change after an app restart`() = runTest {
        var stdout = "1000"
        val h = harness(
            initialSelection = SelectedPackageState.Selected(SelectedPackage(SERIAL, "com.acme.shop")),
            script = { AdbTextResult(AdbOutcome.Completed(0), stdout, "") },
        )
        h.scope.runCurrent()
        h.tracker.state.value shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(1000)))

        stdout = "2000"
        h.scope.launch { h.tracker.refresh() }
        h.scope.runCurrent()

        h.tracker.state.value shouldBe LogcatPidResolution.Resolved(setOf(ProcessId(2000)))
        h.transport.resolvedPackages shouldBe listOf("com.acme.shop", "com.acme.shop")
    }

    @Test
    fun `refresh with nothing selected is a harmless no-op`() = runTest {
        val h = harness()
        h.scope.runCurrent()

        h.scope.launch { h.tracker.refresh() }
        h.scope.runCurrent()

        h.tracker.state.value shouldBe LogcatPidResolution.NotResolved
        h.transport.resolvedPackages shouldBe emptyList()
    }
}
