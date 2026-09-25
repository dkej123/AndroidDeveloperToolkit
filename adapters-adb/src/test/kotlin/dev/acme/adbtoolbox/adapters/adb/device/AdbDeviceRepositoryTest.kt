@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.adapters.adb.device

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private const val ONLINE_DEVICE_OUTPUT = "List of devices attached\n" +
    "R58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3\n"

private const val TWO_DEVICE_OUTPUT = "List of devices attached\n" +
    "R58N90ABCDE            device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:3\n" +
    "192.168.1.42:5555      device product:redfin model:Pixel_5 device:redfin transport_id:5\n"

private fun textResult(stdout: String, outcome: AdbOutcome = AdbOutcome.Completed(0)) =
    AdbTextResult(outcome, stdout, "")

/**
 * [TestScope.advanceUntilIdle] is deliberately never used against [AdbDeviceRepository]: its
 * refresh loop is an intentionally never-completing ticker (task 008 — discovery keeps polling for
 * the life of the owning scope), and `advanceUntilIdle` is documented to never return against a
 * scheduler that keeps scheduling new delayed work forever. [settle] instead advances virtual time
 * by a small, known margin past whatever debounce/poll window a test cares about and lets the
 * scheduler run exactly the tasks that fall within it — deterministic, and immune to that
 * never-terminates trap.
 */
private fun TestScope.settle(margin: Duration = 500.milliseconds) {
    runCurrent()
    advanceTimeBy(margin)
    runCurrent()
}

/**
 * Every [AdbDeviceRepository] here is constructed against its own [CoroutineScope] (never `this`,
 * the [runTest] body's own [TestScope]) so its infinite refresh loop is never mistaken by `runTest`
 * for a leaked/uncompleted test coroutine — cancelling that independent scope (as the disposal test
 * does explicitly, and the others do implicitly when the test ends) is exactly the lifecycle task
 * 008 requires.
 */
class AdbDeviceRepositoryTest {

    @Test
    fun `initial load parses the first devices -l poll into the devices state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(textScript = { textResult(ONLINE_DEVICE_OUTPUT) })
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            pollInterval = 1.hours,
            coalesceWindow = 10.milliseconds,
        )

        settle(20.milliseconds)

        repository.devices.value.map { it.serial } shouldBe listOf(DeviceSerial.of("R58N90ABCDE"))
        transport.textRequests.size shouldBe 1
        (transport.textRequests[0] as AdbServerRequest).arguments shouldBe listOf("devices", "-l")
    }

    @Test
    fun `periodic polling refreshes the device list on its own`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var stdout = ONLINE_DEVICE_OUTPUT
        val transport = FakeAdbTransport(textScript = { textResult(stdout) })
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            pollInterval = 100.milliseconds,
            coalesceWindow = 10.milliseconds,
        )
        settle(20.milliseconds)
        repository.devices.value.size shouldBe 1

        stdout = TWO_DEVICE_OUTPUT
        settle(150.milliseconds)

        repository.devices.value.size shouldBe 2
    }

    @Test
    fun `a device-arrival change signal triggers an immediate refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var stdout = ""
        val transport = FakeAdbTransport(textScript = { textResult(stdout) })
        val changeSignals = MutableSharedFlow<Unit>()
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            changeSignals = changeSignals,
            pollInterval = 1.hours,
            coalesceWindow = 10.milliseconds,
        )
        settle(20.milliseconds)
        repository.devices.value shouldBe emptyList()

        stdout = ONLINE_DEVICE_OUTPUT
        launch { changeSignals.emit(Unit) }
        settle(20.milliseconds)

        repository.devices.value.map { it.serial } shouldBe listOf(DeviceSerial.of("R58N90ABCDE"))
    }

    @Test
    fun `a device-removal change signal drops the device from the observed list`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var stdout = ONLINE_DEVICE_OUTPUT
        val transport = FakeAdbTransport(textScript = { textResult(stdout) })
        val changeSignals = MutableSharedFlow<Unit>()
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            changeSignals = changeSignals,
            pollInterval = 1.hours,
            coalesceWindow = 10.milliseconds,
        )
        settle(20.milliseconds)
        repository.devices.value.size shouldBe 1

        stdout = "List of devices attached\n"
        launch { changeSignals.emit(Unit) }
        settle(20.milliseconds)

        repository.devices.value shouldBe emptyList()
    }

    @Test
    fun `an upstream transport failure leaves the previously observed device list unchanged`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var result = textResult(ONLINE_DEVICE_OUTPUT)
        val transport = FakeAdbTransport(textScript = { result })
        val changeSignals = MutableSharedFlow<Unit>()
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            changeSignals = changeSignals,
            pollInterval = 1.hours,
            coalesceWindow = 10.milliseconds,
        )
        settle(20.milliseconds)
        val stableList = repository.devices.value
        stableList.size shouldBe 1

        result = AdbTextResult(AdbOutcome.TransportFailure("adb server unreachable"), "", "adb: no devices/emulators found")
        launch { changeSignals.emit(Unit) }
        settle(20.milliseconds)

        repository.devices.value shouldBe stableList
    }

    @Test
    fun `rapid-fire change signals within the coalesce window trigger only one refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(textScript = { textResult(ONLINE_DEVICE_OUTPUT) })
        val changeSignals = MutableSharedFlow<Unit>()
        AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            changeSignals = changeSignals,
            pollInterval = 1.hours,
            coalesceWindow = 200.milliseconds,
        )
        settle(20.milliseconds)
        val callsAfterInitialLoad = transport.textRequests.size

        repeat(5) {
            launch { changeSignals.emit(Unit) }
            advanceTimeBy(20.milliseconds)
            runCurrent()
        }
        settle(250.milliseconds)

        (transport.textRequests.size - callsAfterInitialLoad) shouldBe 1
    }

    @Test
    fun `an explicit manual refresh call triggers an immediate refresh outside the poll cadence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var stdout = ""
        val transport = FakeAdbTransport(textScript = { textResult(stdout) })
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            pollInterval = 1.hours,
            coalesceWindow = 10.milliseconds,
        )
        settle(20.milliseconds)
        repository.devices.value shouldBe emptyList()

        stdout = ONLINE_DEVICE_OUTPUT
        launch { repository.refresh() }
        settle(20.milliseconds)

        repository.devices.value.map { it.serial } shouldBe listOf(DeviceSerial.of("R58N90ABCDE"))
    }

    @Test
    fun `cancelling the owning scope tears down the refresh loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val childScope = CoroutineScope(dispatcher)
        val transport = FakeAdbTransport(textScript = { textResult(ONLINE_DEVICE_OUTPUT) })
        AdbDeviceRepository(
            scope = childScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            pollInterval = 100.milliseconds,
            coalesceWindow = 10.milliseconds,
        )
        settle(20.milliseconds)
        advanceTimeBy(250.milliseconds)
        runCurrent()
        val callsBeforeCancel = transport.textRequests.size
        (callsBeforeCancel > 1) shouldBe true

        childScope.cancel()
        advanceTimeBy(10.hours)
        runCurrent()

        transport.textRequests.size shouldBe callsBeforeCancel
    }

    @Test
    fun `a failed devices query is reported as a list error and cleared by the next successful query`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var failing = true
        val transport = FakeAdbTransport(
            textScript = {
                if (failing) {
                    AdbTextResult(AdbOutcome.TransportFailure("adb executable not found (tried: PathFallback)"), "", "")
                } else {
                    textResult(ONLINE_DEVICE_OUTPUT)
                }
            },
        )
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            pollInterval = 1.hours,
            coalesceWindow = 10.milliseconds,
        )

        settle(20.milliseconds)
        repository.listError.value shouldBe "adb executable not found (tried: PathFallback)"

        failing = false
        repository.refresh()
        settle(20.milliseconds)

        repository.listError.value shouldBe null
        repository.devices.value.size shouldBe 1
    }

    @Test
    fun `a non-zero adb exit reports its stderr as the list error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "adb: cannot connect to daemon\n") },
        )
        val repository = AdbDeviceRepository(
            scope = backgroundScope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            binaryTransport = transport,
            pollInterval = 1.hours,
            coalesceWindow = 10.milliseconds,
        )

        settle(20.milliseconds)

        repository.listError.value shouldBe "adb devices failed: adb: cannot connect to daemon"
    }
}
