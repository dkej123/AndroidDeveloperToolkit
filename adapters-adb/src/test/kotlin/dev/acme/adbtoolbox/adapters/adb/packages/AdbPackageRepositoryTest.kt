@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.adb.ShellToken
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import dev.acme.adbtoolbox.domain.packages.PackageListScope
import dev.acme.adbtoolbox.domain.packages.PackageListState
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private fun TestScope.settle(margin: Duration = 50.milliseconds) {
    runCurrent()
    advanceTimeBy(margin)
    runCurrent()
}

private fun textResult(stdout: String, outcome: AdbOutcome = AdbOutcome.Completed(0)) =
    AdbTextResult(outcome, stdout, "")

private fun listCommandOf(request: AdbRequest): String? =
    ((request as? AdbDeviceRequest)?.operation as? AdbOperation.Shell)
        ?.takeIf { (it.command.tokens.getOrNull(2) as? ShellToken.Literal)?.text == "packages" }
        ?.command?.render()

private fun metadataPackageOf(request: AdbRequest): String? {
    val shell = (request as? AdbDeviceRequest)?.operation as? AdbOperation.Shell ?: return null
    if ((shell.command.tokens.getOrNull(0) as? ShellToken.Literal)?.text != "dumpsys") return null
    return (shell.command.tokens.getOrNull(2) as? ShellToken.Value)?.value?.raw
}

private fun dumpsysOutput(label: String? = "App", debuggable: Boolean = false): String {
    val flags = if (debuggable) "flags=[ DEBUGGABLE HAS_CODE ]" else "flags=[ HAS_CODE ]"
    val labelLine = if (label != null) "applicationLabel=$label" else ""
    return "Packages:\n  Package [pkg]:\n    $flags\n    $labelLine\n"
}

/**
 * A hand-written [AdbTransport] double (rather than [FakeAdbTransport], whose `textScript` is a
 * plain, non-suspending function) so metadata calls can genuinely overlap in virtual time — the
 * only way to observe whether [AdbPackageRepository]'s enrichment fan-out actually respects its
 * concurrency bound rather than merely calling through a bound-looking API.
 */
private class ConcurrencyTrackingTransport(
    private val artificialDelay: Duration,
    private val packageCount: Int,
) : AdbTransport {
    private val mutex = Mutex()
    private var inFlight = 0
    var peakInFlight = 0
        private set
    var metadataCalls = 0
        private set

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        if (listCommandOf(request) != null) {
            return textResult((1..packageCount).joinToString("\n") { "package:com.acme.app$it" })
        }
        mutex.withLock {
            inFlight++
            metadataCalls++
            if (inFlight > peakInFlight) peakInFlight = inFlight
        }
        delay(artificialDelay)
        mutex.withLock { inFlight-- }
        return textResult(dumpsysOutput(label = "App"))
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}

class AdbPackageRepositoryTest {

    @Test
    fun `refresh parses the package list into an immediate fallback-labeled snapshot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(
            textScript = { request ->
                if (listCommandOf(request) != null) {
                    textResult("package:com.acme.shop\npackage:com.acme.other\n")
                } else {
                    textResult(dumpsysOutput(label = null))
                }
            },
        )
        val repository = AdbPackageRepository(backgroundScope, TestDispatcherProviderFixture(dispatcher), transport)

        repository.refresh(DeviceSerial.of("R58N90ABCDE"), PackageListScope.User)
        runCurrent()

        val content = repository.state.value as PackageListState.Content
        content.serial shouldBe DeviceSerial.of("R58N90ABCDE")
        content.scope shouldBe PackageListScope.User
        content.packages.map { it.packageName } shouldBe listOf("com.acme.other", "com.acme.shop")
        content.packages.all { !it.labelResolved && it.label == it.packageName } shouldBe true
    }

    @Test
    fun `renders the user-scoped pm list packages -3 command`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(textScript = { textResult("") })
        val repository = AdbPackageRepository(backgroundScope, TestDispatcherProviderFixture(dispatcher), transport)

        repository.refresh(DeviceSerial.of("emulator-5554"), PackageListScope.User)
        runCurrent()

        transport.textRequests.firstNotNullOf(::listCommandOf) shouldBe "pm list packages -3"
    }

    @Test
    fun `renders the all-scoped pm list packages command with no extra flag`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(textScript = { textResult("") })
        val repository = AdbPackageRepository(backgroundScope, TestDispatcherProviderFixture(dispatcher), transport)

        repository.refresh(DeviceSerial.of("emulator-5554"), PackageListScope.All)
        runCurrent()

        transport.textRequests.firstNotNullOf(::listCommandOf) shouldBe "pm list packages"
    }

    @Test
    fun `bounded enrichment fetches metadata for every package and never exceeds the concurrency bound`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val maxConcurrency = 2
        val transport = ConcurrencyTrackingTransport(artificialDelay = 30.milliseconds, packageCount = 6)
        val repository = AdbPackageRepository(
            backgroundScope,
            TestDispatcherProviderFixture(dispatcher),
            transport,
            maxConcurrentEnrichment = maxConcurrency,
        )

        repository.refresh(DeviceSerial.of("emulator-5554"), PackageListScope.User)
        settle(200.milliseconds)

        transport.metadataCalls shouldBe 6
        (transport.peakInFlight <= maxConcurrency) shouldBe true
        (transport.peakInFlight >= 1) shouldBe true
        val content = repository.state.value as PackageListState.Content
        content.packages.size shouldBe 6
        content.packages.all { it.labelResolved } shouldBe true
    }

    @Test
    fun `a metadata parse failure falls back to the package name without dropping the entry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(
            textScript = { request ->
                when {
                    listCommandOf(request) != null -> textResult("package:com.acme.shop\n")
                    else -> textResult("")
                }
            },
        )
        val repository = AdbPackageRepository(backgroundScope, TestDispatcherProviderFixture(dispatcher), transport)

        repository.refresh(DeviceSerial.of("emulator-5554"), PackageListScope.User)
        settle()

        val content = repository.state.value as PackageListState.Content
        content.packages shouldBe listOf(PackageEntry.unresolved("com.acme.shop"))
    }

    @Test
    fun `partial enrichment success keeps the whole list while some entries fall back`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(
            textScript = { request ->
                when {
                    listCommandOf(request) != null -> textResult("package:com.acme.good\npackage:com.acme.bad\n")
                    metadataPackageOf(request) == "com.acme.good" -> textResult(dumpsysOutput(label = "Good App"))
                    else -> textResult("")
                }
            },
        )
        val repository = AdbPackageRepository(backgroundScope, TestDispatcherProviderFixture(dispatcher), transport)

        repository.refresh(DeviceSerial.of("emulator-5554"), PackageListScope.User)
        settle()

        val content = repository.state.value as PackageListState.Content
        content.packages.size shouldBe 2
        content.packages.first { it.packageName == "com.acme.good" }.label shouldBe "Good App"
        content.packages.first { it.packageName == "com.acme.bad" }.let {
            it.label shouldBe "com.acme.bad"
            it.labelResolved shouldBe false
        }
    }

    @Test
    fun `an outright list failure surfaces as an error state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.TransportFailure("device offline"), "", "error") },
        )
        val repository = AdbPackageRepository(backgroundScope, TestDispatcherProviderFixture(dispatcher), transport)

        repository.refresh(DeviceSerial.of("emulator-5554"), PackageListScope.User)
        runCurrent()

        val error = repository.state.value as PackageListState.Error
        error.message shouldBe "device offline"
    }

    @Test
    fun `refreshing a new serial discards a slow in-flight enrichment for the previous serial`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val staleSerial = DeviceSerial.of("stale-serial")
        val freshSerial = DeviceSerial.of("fresh-serial")
        val transport = FakeAdbTransport(
            textScript = { request ->
                when {
                    listCommandOf(request) != null && (request as AdbDeviceRequest).serial == staleSerial ->
                        textResult("package:com.acme.stale\n")
                    listCommandOf(request) != null && (request as AdbDeviceRequest).serial == freshSerial ->
                        textResult("package:com.acme.fresh\n")
                    metadataPackageOf(request) == "com.acme.stale" ->
                        // Never actually applied: the repository must not let a superseded
                        // serial's enrichment response land in state once a fresh refresh fires.
                        textResult(dumpsysOutput(label = "Should never be observed"))
                    else -> textResult(dumpsysOutput(label = "Fresh App"))
                }
            },
        )
        val repository = AdbPackageRepository(backgroundScope, TestDispatcherProviderFixture(dispatcher), transport)

        repository.refresh(staleSerial, PackageListScope.User)
        runCurrent()
        (repository.state.value as PackageListState.Content).serial shouldBe staleSerial

        repository.refresh(freshSerial, PackageListScope.User)
        settle()

        val content = repository.state.value as PackageListState.Content
        content.serial shouldBe freshSerial
        content.packages.map { it.packageName } shouldBe listOf("com.acme.fresh")
        content.packages.single().label shouldBe "Fresh App"
    }

    @Test
    fun `cancelling the owning scope tears down in-flight enrichment and any queued-behind-the-bound work`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val childScope = CoroutineScope(dispatcher)
        // 6 packages against the default bound of 4: 2 remain queued on the semaphore, never
        // having called executeText yet, when the scope is cancelled.
        val transport = ConcurrencyTrackingTransport(artificialDelay = 100.milliseconds, packageCount = 6)
        AdbPackageRepository(childScope, TestDispatcherProviderFixture(dispatcher), transport)
            .refresh(DeviceSerial.of("emulator-5554"), PackageListScope.User)
        runCurrent()

        val callsRightAfterCancel = transport.metadataCalls
        callsRightAfterCancel shouldBe 4
        childScope.cancel()
        advanceTimeBy(10.seconds)
        runCurrent()

        transport.metadataCalls shouldBe callsRightAfterCancel
    }
}
