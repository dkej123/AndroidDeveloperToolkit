@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.discovery.DiscoveredTool
import dev.acme.adbtoolbox.domain.discovery.DiscoveryError
import dev.acme.adbtoolbox.domain.discovery.DiscoveryOutcome
import dev.acme.adbtoolbox.domain.discovery.FakeToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolExecutablePath
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.discovery.ToolSource
import dev.acme.adbtoolbox.domain.discovery.ToolVersion
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.mirroring.MirroringExitReason
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringSessionError
import dev.acme.adbtoolbox.domain.mirroring.MirroringSessionState
import dev.acme.adbtoolbox.domain.mirroring.MirroringStartOutcome
import dev.acme.adbtoolbox.domain.mirroring.buildScrcpyArguments
import dev.acme.adbtoolbox.domain.process.ProcessCommand
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import dev.acme.adbtoolbox.domain.process.ProcessRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

/** A deterministic [ProcessExecutor] test double that hands each request to a caller-supplied flow
 * factory — unlike [dev.acme.adbtoolbox.domain.process.FakeProcessExecutor]'s fixed event list,
 * this lets a test simulate a session that runs indefinitely (via [awaitCancellation]) until the
 * manager cancels it, which a finite scripted list cannot represent. */
private class ScriptedProcessExecutor(
    private val script: (ProcessRequest) -> Flow<ProcessEvent>,
) : ProcessExecutor {
    val requests = mutableListOf<ProcessRequest>()

    override fun execute(request: ProcessRequest): Flow<ProcessEvent> {
        requests += request
        return script(request)
    }
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")
private val scrcpyVersion = ToolVersion.of("2.7")

private fun discoveredScrcpy(path: String = "/opt/homebrew/bin/scrcpy") = DiscoveredTool(
    id = ToolId.Scrcpy,
    source = ToolSource.PathFallback,
    path = ToolExecutablePath.of(path),
    version = scrcpyVersion,
)

/** A scrcpy process that has "started" (emitted output) and then runs forever until cancelled —
 * models the real, indefinite-until-stopped mirroring window. */
private fun runningForeverFlow(): Flow<ProcessEvent> = flow {
    emit(ProcessEvent.StderrText("INFO: Device: Pixel 8 Pro"))
    awaitCancellation()
}

private fun exitsWithFlow(exitCode: Int): Flow<ProcessEvent> = flow {
    emit(ProcessEvent.StderrText("INFO: Device: Pixel 8 Pro"))
    emit(ProcessEvent.Completed(ProcessOutcome.Completed(exitCode)))
}

class MirroringSessionManagerTest {

    private fun harness(
        toolOutcome: (ToolId) -> DiscoveryOutcome = { DiscoveryOutcome.Found(discoveredScrcpy()) },
        script: (ProcessRequest) -> Flow<ProcessEvent> = { runningForeverFlow() },
    ): Triple<TestScope, ScriptedProcessExecutor, MirroringSessionManager> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executor = ScriptedProcessExecutor(script)
        val manager = MirroringSessionManager(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            toolLocator = FakeToolLocator(toolOutcome),
            processExecutor = executor,
        )
        return Triple(scope, executor, manager)
    }

    @Test
    fun `a serial with no session started is Idle`() {
        val (_, _, manager) = harness()

        manager.stateFor(serialA).value shouldBe MirroringSessionState.Idle(serialA)
    }

    @Test
    fun `starting transitions through Starting into Running once the process produces output`() = runTest {
        val (scope, _, manager) = harness()

        manager.start(serialA) shouldBe MirroringStartOutcome.Started
        manager.stateFor(serialA).value shouldBe MirroringSessionState.Starting(serialA)

        scope.advanceTimeBy(1)
        scope.runCurrent()

        val running = manager.stateFor(serialA).value
        running.shouldBeInstanceOf<MirroringSessionState.Running>()
        (running as MirroringSessionState.Running).scrcpyVersion shouldBe scrcpyVersion
    }

    @Test
    fun `starting a second session for the same serial while one is active is rejected, not a duplicate process`() =
        runTest {
            val (scope, executor, manager) = harness()

            manager.start(serialA) shouldBe MirroringStartOutcome.Started
            scope.advanceTimeBy(1)
            scope.runCurrent()

            manager.start(serialA) shouldBe MirroringStartOutcome.Rejected

            executor.requests.size shouldBe 1
        }

    @Test
    fun `sessions for different serials are independent — starting B does not disturb A`() = runTest {
        val (scope, executor, manager) = harness()

        manager.start(serialA) shouldBe MirroringStartOutcome.Started
        scope.advanceTimeBy(1)
        scope.runCurrent()

        manager.start(serialB) shouldBe MirroringStartOutcome.Started
        scope.advanceTimeBy(1)
        scope.runCurrent()

        manager.stateFor(serialA).value.shouldBeInstanceOf<MirroringSessionState.Running>()
        manager.stateFor(serialB).value.shouldBeInstanceOf<MirroringSessionState.Running>()
        executor.requests.size shouldBe 2
    }

    @Test
    fun `structured arguments encode the requested serial and options, never a shell string`() = runTest {
        val (scope, executor, manager) = harness()
        val options = MirroringOptions(stayAwake = true, maxSize = 1920)

        manager.start(serialA, options)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        executor.requests.single().command shouldBe ProcessCommand(
            executable = "/opt/homebrew/bin/scrcpy",
            arguments = buildScrcpyArguments(serialA, options),
        )
    }

    @Test
    fun `stopping a running session transitions to Stopping then Exited with a Requested reason`() = runTest {
        val (scope, _, manager) = harness()

        manager.start(serialA)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        manager.stop(serialA)
        manager.stateFor(serialA).value shouldBe MirroringSessionState.Stopping(serialA)

        scope.advanceTimeBy(1)
        scope.runCurrent()

        manager.stateFor(serialA).value shouldBe MirroringSessionState.Exited(serialA, MirroringExitReason.Requested)
    }

    @Test
    fun `starting again while a prior session is still Stopping is rejected, never a second owned process`() =
        runTest {
            val (scope, executor, manager) = harness()

            manager.start(serialA) shouldBe MirroringStartOutcome.Started
            scope.advanceTimeBy(1)
            scope.runCurrent()

            manager.stop(serialA)
            manager.stateFor(serialA).value shouldBe MirroringSessionState.Stopping(serialA)

            // The teardown coroutine has not yet completed (no runCurrent() since stop()) — a
            // caller racing a second start() during this window must not spawn a second process.
            manager.start(serialA) shouldBe MirroringStartOutcome.Rejected

            scope.advanceTimeBy(1)
            scope.runCurrent()

            executor.requests.size shouldBe 1
        }

    @Test
    fun `no orphan process survives stop — the underlying process request is cancelled`() = runTest {
        var cancelled = false
        val (scope, _, manager) = harness(script = {
            flow {
                emit(ProcessEvent.StderrText("INFO: Device"))
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        })

        manager.start(serialA)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        manager.stop(serialA)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        cancelled shouldBe true
    }

    @Test
    fun `the process exiting on its own with code 0 is a distinct ExternalWindowExit reason, not Requested`() =
        runTest {
            val (scope, _, manager) = harness(script = { exitsWithFlow(0) })

            manager.start(serialA)
            scope.advanceTimeBy(1)
            scope.runCurrent()

            manager.stateFor(serialA).value shouldBe
                MirroringSessionState.Exited(serialA, MirroringExitReason.ExternalWindowExit)
        }

    @Test
    fun `a new start is accepted once a prior session for the same serial has Exited, launching a fresh process`() =
        runTest {
            val (scope, executor, manager) = harness(script = { exitsWithFlow(0) })

            manager.start(serialA) shouldBe MirroringStartOutcome.Started
            scope.advanceTimeBy(1)
            scope.runCurrent()
            manager.stateFor(serialA).value shouldBe
                MirroringSessionState.Exited(serialA, MirroringExitReason.ExternalWindowExit)

            manager.start(serialA) shouldBe MirroringStartOutcome.Started
            manager.stateFor(serialA).value shouldBe MirroringSessionState.Starting(serialA)

            scope.advanceTimeBy(1)
            scope.runCurrent()

            executor.requests.size shouldBe 2
        }

    @Test
    fun `an unexpected non-zero exit such as device disconnect is a ProcessExited reason`() =
        runTest {
            val (scope, _, manager) = harness(script = { exitsWithFlow(1) })

            manager.start(serialA)
            scope.advanceTimeBy(1)
            scope.runCurrent()

            manager.stateFor(serialA).value shouldBe
                MirroringSessionState.Exited(serialA, MirroringExitReason.ProcessExited(1))
        }

    @Test
    fun `a timed-out process outcome is reported as a Timeout exit reason`() = runTest {
        val (scope, _, manager) = harness(script = {
            flow {
                emit(ProcessEvent.StderrText("INFO: Device"))
                emit(ProcessEvent.Completed(ProcessOutcome.TimedOut))
            }
        })

        manager.start(serialA)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        manager.stateFor(serialA).value shouldBe MirroringSessionState.Exited(serialA, MirroringExitReason.Timeout)
    }

    @Test
    fun `a process start failure before any output is a typed StartFailure error, not Exited`() = runTest {
        val (scope, _, manager) = harness(script = {
            flowOf(ProcessEvent.Completed(ProcessOutcome.StartFailure("No such file or directory")))
        })

        manager.start(serialA)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        val state = manager.stateFor(serialA).value
        state.shouldBeInstanceOf<MirroringSessionState.Error>()
        (state as MirroringSessionState.Error).error.shouldBeInstanceOf<MirroringSessionError.StartFailure>()
    }

    @Test
    fun `missing scrcpy produces an actionable typed ToolUnavailable error carrying the discovery failure`() =
        runTest {
            val discoveryError = DiscoveryError.ToolNotFound(ToolId.Scrcpy, listOf(ToolSource.PathFallback))
            val (scope, executor, manager) = harness(toolOutcome = { DiscoveryOutcome.Failed(discoveryError) })

            manager.start(serialA)
            scope.advanceTimeBy(1)
            scope.runCurrent()

            val state = manager.stateFor(serialA).value
            state.shouldBeInstanceOf<MirroringSessionState.Error>()
            (state as MirroringSessionState.Error).error shouldBe MirroringSessionError.ToolUnavailable(discoveryError)
            executor.requests shouldBe emptyList()
        }

    @Test
    fun `disposal via scope cancellation tears down a running session's process without an explicit stop`() =
        runTest {
            var cancelled = false
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sessionScope = CoroutineScope(dispatcher + Job())
            val executor = ScriptedProcessExecutor {
                flow {
                    emit(ProcessEvent.StderrText("INFO: Device"))
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
            val manager = MirroringSessionManager(
                scope = sessionScope,
                dispatchers = TestDispatcherProviderFixture(dispatcher),
                toolLocator = FakeToolLocator { DiscoveryOutcome.Found(discoveredScrcpy()) },
                processExecutor = executor,
            )

            manager.start(serialA)
            advanceTimeBy(1)
            runCurrent()

            sessionScope.cancel()
            runCurrent()

            cancelled shouldBe true
        }

    @Test
    fun `runningProcessesFor reports an active session and nothing for an idle serial or null serial`() = runTest {
        val (scope, _, manager) = harness()

        manager.runningProcessesFor(serialA) shouldBe emptyList()
        manager.runningProcessesFor(null) shouldBe emptyList()

        manager.start(serialA)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        manager.runningProcessesFor(serialA).size shouldBe 1
        manager.runningProcessesFor(serialB) shouldBe emptyList()
        manager.runningProcessesFor(null) shouldBe emptyList()
    }
}
