@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.network

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.network.FakeHostNetworkInfo
import dev.acme.adbtoolbox.domain.network.FakeNetworkRecentsPersistence
import dev.acme.adbtoolbox.domain.network.HostInterfaceEnumeration
import dev.acme.adbtoolbox.domain.network.NetworkInterfaceCandidate
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyHost
import dev.acme.adbtoolbox.domain.network.ProxyHostResult
import dev.acme.adbtoolbox.domain.network.ProxyPort
import dev.acme.adbtoolbox.domain.network.ProxyPortResult
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.domain.process.ByteSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

internal class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineState(serial: DeviceSerial) =
    SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))

private fun offlineState(serial: DeviceSerial) =
    SelectedDeviceState.Offline(Device(serial = serial, state = DeviceConnectionState.Offline))

private fun completedText(stdout: String, exitCode: Int? = 0) =
    AdbTextResult(AdbOutcome.Completed(exitCode), stdout = stdout, stderr = "")

/**
 * [ProxyController] orchestrates read-on-selection/reconnect and enable/reset with readback-truth
 * (task 030): every mutation's final state comes from a subsequent `settings get`, concurrent
 * writes are serialized through one channel, and results for a superseded serial are suppressed.
 */
class ProxyControllerTest {

    private fun harness(
        transport: AdbTransport,
        hostNetworkInfo: FakeHostNetworkInfo = FakeHostNetworkInfo(),
        recentsPersistence: FakeNetworkRecentsPersistence = FakeNetworkRecentsPersistence(),
    ): Triple<TestScope, MutableStateFlow<SelectedDeviceState>, ProxyController> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.None)
        val controller = ProxyController(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            transport = transport,
            selectedDeviceState = selectedDeviceState,
            hostNetworkInfo = hostNetworkInfo,
            recentsPersistence = recentsPersistence,
        )
        return Triple(scope, selectedDeviceState, controller)
    }

    @Test
    fun `selecting an online device reads proxy state, targeting the exact serial`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText("10.0.4.117:8888") })
        val (scope, selectedDeviceState, controller) = harness(transport)

        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        val state = controller.state.value
        state.serial shouldBe serialA
        state.isDeviceEligible shouldBe true
        state.readState.shouldBeInstanceOf<ProxyReadState.Active>()
        val request = transport.textRequests.single() as AdbDeviceRequest
        request.serial shouldBe serialA
    }

    @Test
    fun `an off readback is reflected as Disabled`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)

        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.state.value.readState shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `enable issues the put command then reads back, and the final state is the readback value`() = runTest {
        val responses = mutableListOf("10.0.4.117:8888")
        val transport = FakeAdbTransport(textScript = { completedText(responses.removeFirst()) })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        responses += "ignored-put-response"
        responses += "10.0.4.117:8888"

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "8888"))
        scope.runCurrent()

        val state = controller.state.value
        state.readState shouldBe ProxyReadState.Active(
            ProxyEndpoint(
                (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
                (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
            ),
        )
        state.isBusy shouldBe false
        // one read on selection, one put on enable, one read for readback
        transport.textRequests.size shouldBe 3
        val putRequest = transport.textRequests[1] as AdbDeviceRequest
        val shellOp = putRequest.operation as AdbOperation.Shell
        shellOp.command.render() shouldBe "settings put global http_proxy '10.0.4.117:8888'"
    }

    @Test
    fun `reset issues the colon-zero command then reads back`() = runTest {
        val responses = mutableListOf("10.0.4.117:8888", "ignored-put-response", ":0")
        val transport = FakeAdbTransport(textScript = { completedText(responses.removeFirst()) })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.Reset)
        scope.runCurrent()

        controller.state.value.readState shouldBe ProxyReadState.Disabled
        val putRequest = transport.textRequests[1] as AdbDeviceRequest
        val shellOp = putRequest.operation as AdbOperation.Shell
        shellOp.command.render() shouldBe "settings put global http_proxy ':0'"
    }

    @Test
    fun `an invalid host issues zero commands`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val requestsBeforeEnable = transport.textRequests.size

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4 .117", portInput = "8888"))
        scope.runCurrent()

        transport.textRequests.size shouldBe requestsBeforeEnable
        controller.state.value.error shouldBe "Host must not contain whitespace or control characters"
    }

    @Test
    fun `an invalid port issues zero commands`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val requestsBeforeEnable = transport.textRequests.size

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "70000"))
        scope.runCurrent()

        transport.textRequests.size shouldBe requestsBeforeEnable
        controller.state.value.error shouldBe "Port must be 1-65535"
    }

    @Test
    fun `enable with no device selected issues zero commands`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, _, controller) = harness(transport)
        scope.runCurrent()

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "8888"))
        scope.runCurrent()

        transport.textRequests.size shouldBe 0
        controller.state.value.error shouldBe "No device selected"
    }

    @Test
    fun `a mismatch between the requested endpoint and the device readback reflects the device's truth`() = runTest {
        val responses = mutableListOf("10.0.4.117:8888")
        val transport = FakeAdbTransport(textScript = { completedText(responses.removeFirst()) })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        // Device reports a different endpoint than what was requested (e.g. OEM normalization).
        responses += "ignored-put-response"
        responses += "10.0.4.117:9999"

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "8888"))
        scope.runCurrent()

        val active = controller.state.value.readState as ProxyReadState.Active
        active.endpoint.port.value shouldBe 9999
    }

    @Test
    fun `a write failure surfaces an error without a false Active state`() = runTest {
        val responses = mutableListOf("10.0.4.117:8888")
        var callCount = 0
        val transport = FakeAdbTransport(
            textScript = {
                callCount++
                if (callCount == 1) {
                    completedText(responses.removeFirst())
                } else {
                    AdbTextResult(AdbOutcome.TransportFailure("device offline"), stdout = "", stderr = "")
                }
            },
        )
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "8888"))
        scope.runCurrent()

        val state = controller.state.value
        state.error shouldBe "device offline"
        state.isBusy shouldBe false
    }

    @Test
    fun `a timeout surfaces as an error`() = runTest {
        var callCount = 0
        val transport = FakeAdbTransport(
            textScript = {
                callCount++
                if (callCount == 1) completedText(":0") else AdbTextResult(AdbOutcome.TimedOut, stdout = "", stderr = "")
            },
        )
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.Reset)
        scope.runCurrent()

        controller.state.value.error shouldBe "Timed out"
    }

    @Test
    fun `rapid apply calls are serialized, never issuing overlapping write requests`() = runTest {
        val issuedOrder = mutableListOf<String>()
        val transport = FakeAdbTransport(
            textScript = { request ->
                val op = (request as AdbDeviceRequest).operation as AdbOperation.Shell
                issuedOrder += op.command.render()
                completedText("10.0.4.117:1111")
            },
        )
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "1111"))
        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "2222"))
        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "3333"))
        scope.runCurrent()

        // initial read + 3x (put + read) = 7 sequential, non-interleaved calls
        issuedOrder.size shouldBe 7
        issuedOrder[1] shouldBe "settings put global http_proxy '10.0.4.117:1111'"
        issuedOrder[3] shouldBe "settings put global http_proxy '10.0.4.117:2222'"
        issuedOrder[5] shouldBe "settings put global http_proxy '10.0.4.117:3333'"
    }

    @Test
    fun `a device switch mid-apply suppresses the stale result for the old serial`() = runTest {
        // The enable's "put" call is in flight (delayed) when the device switches; its result must
        // never land, and the fresh read triggered by the switch must be the final state.
        val transport = DelayedAdbTransport(
            listOf(
                DelayedResponse(delayMillis = 0, stdout = ":0"), // initial read on selecting A
                DelayedResponse(delayMillis = 50, stdout = "ignored"), // enable's put, in flight during the switch
                DelayedResponse(delayMillis = 0, stdout = ":0"), // read triggered by selecting B
            ),
        )
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "8888"))
        scope.runCurrent() // the put call starts and suspends on its delay; nothing else is ready yet

        // Device switches while the put call is still in flight.
        selectedDeviceState.value = onlineState(serialB)
        scope.runCurrent() // the switch is observed and a fresh read for B is queued behind the busy actor

        scope.advanceTimeBy(50)
        scope.runCurrent() // the stale put call resolves now but is discarded; the queued read for B then runs

        val state = controller.state.value
        state.serial shouldBe serialB
        state.readState shouldBe ProxyReadState.Disabled
        // initial read(A), the put(A), and the read(B) — the put's own readback never ran because
        // the generation check after the put short-circuits before reaching it.
        transport.calls.size shouldBe 3
    }

    @Test
    fun `reconnect of the same serial triggers a fresh read`() = runTest {
        var callCount = 0
        val transport = FakeAdbTransport(
            textScript = {
                callCount++
                if (callCount == 1) completedText(":0") else completedText("10.0.4.117:8888")
            },
        )
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        controller.state.value.readState shouldBe ProxyReadState.Disabled

        selectedDeviceState.value = offlineState(serialA)
        scope.runCurrent()
        controller.state.value.isDeviceEligible shouldBe false
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.state.value.readState.shouldBeInstanceOf<ProxyReadState.Active>()
        callCount shouldBe 2
    }

    @Test
    fun `a device-list refresh that still reports the same serial online does not trigger a redundant read`() = runTest {
        var callCount = 0
        val transport = FakeAdbTransport(textScript = { callCount++; completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        // Same serial, still Online, but a distinct Device instance (e.g. adb reassigned transportId) —
        // a real repository re-emission, not a genuine reconnect.
        selectedDeviceState.value = SelectedDeviceState.Online(
            Device(serial = serialA, state = DeviceConnectionState.Online, transportId = "7"),
        )
        scope.runCurrent()

        callCount shouldBe 1
    }

    @Test
    fun `cancellation of the owning scope leaves state untouched, with no further processing`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val lastState = controller.state.value

        scope.cancel()
        controller.handle(ProxyIntent.Reset)
        scope.runCurrent()

        controller.state.value shouldBe lastState
    }

    @Test
    fun `editing the host field live-validates without issuing any command`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val requestsBefore = transport.textRequests.size

        controller.handle(ProxyIntent.EditHost("10.0.4 .117"))
        scope.runCurrent()

        controller.state.value.hostInput shouldBe "10.0.4 .117"
        controller.state.value.hostError shouldBe "Host must not contain whitespace or control characters"
        controller.state.value.canSubmit shouldBe false
        transport.textRequests.size shouldBe requestsBefore
    }

    @Test
    fun `editing the port field live-validates without issuing any command`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.EditHost("10.0.4.117"))
        controller.handle(ProxyIntent.EditPort("70000"))
        scope.runCurrent()

        controller.state.value.portError shouldBe "Port must be 1-65535"
        controller.state.value.canSubmit shouldBe false
    }

    @Test
    fun `a fully valid form can submit`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val (scope, selectedDeviceState, controller) = harness(transport)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.EditHost("10.0.4.117"))
        controller.handle(ProxyIntent.EditPort("8888"))
        scope.runCurrent()

        controller.state.value.canSubmit shouldBe true
    }

    @Test
    fun `selecting a recent fills the fields without enabling`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val recents = FakeNetworkRecentsPersistence(
            initial = listOf(
                ProxyEndpoint(
                    (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
                    (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
                ),
            ),
        )
        val (scope, selectedDeviceState, controller) = harness(transport, recentsPersistence = recents)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        val requestsBefore = transport.textRequests.size

        val endpoint = controller.state.value.recents.single()
        controller.handle(ProxyIntent.SelectRecent(endpoint))
        scope.runCurrent()

        controller.state.value.hostInput shouldBe "10.0.4.117"
        controller.state.value.portInput shouldBe "8888"
        transport.textRequests.size shouldBe requestsBefore
    }

    @Test
    fun `enabling successfully records the endpoint as the most recent, persisted entry`() = runTest {
        val responses = mutableListOf("10.0.4.117:8888")
        val transport = FakeAdbTransport(textScript = { completedText(responses.removeFirst()) })
        val recents = FakeNetworkRecentsPersistence()
        val (scope, selectedDeviceState, controller) = harness(transport, recentsPersistence = recents)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        responses += "ignored-put-response"
        responses += "10.0.4.117:8888"

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "8888"))
        scope.runCurrent()

        val expected = ProxyEndpoint(
            (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
            (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
        )
        controller.state.value.recents shouldBe listOf(expected)
        recents.writes.last() shouldBe listOf(expected)
    }

    @Test
    fun `a recents persistence failure is surfaced without stopping later proxy work`() = runTest {
        val responses = mutableListOf(":0", "ignored-put-response", "10.0.4.117:8888", "ignored-reset", ":0")
        val transport = FakeAdbTransport(textScript = { completedText(responses.removeFirst()) })
        val recents = FakeNetworkRecentsPersistence().apply {
            writeFailure = IllegalStateException("disk full")
        }
        val (scope, selectedDeviceState, controller) = harness(transport, recentsPersistence = recents)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.Enable(hostInput = "10.0.4.117", portInput = "8888"))
        scope.runCurrent()

        controller.state.value.error shouldBe "Failed to save recent proxy endpoints: disk full"

        controller.handle(ProxyIntent.Reset)
        scope.runCurrent()

        controller.state.value.readState shouldBe ProxyReadState.Disabled
        controller.state.value.error shouldBe null
        transport.textRequests.size shouldBe 5
    }

    @Test
    fun `a device switch preserves recents and form text but resets per-serial truth`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val recents = FakeNetworkRecentsPersistence(
            initial = listOf(
                ProxyEndpoint(
                    (ProxyHost.parse("10.0.4.117") as ProxyHostResult.Valid).host,
                    (ProxyPort.parse(8888) as ProxyPortResult.Valid).port,
                ),
            ),
        )
        val (scope, selectedDeviceState, controller) = harness(transport, recentsPersistence = recents)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()
        controller.handle(ProxyIntent.EditHost("proxy.acme.dev"))
        scope.runCurrent()

        selectedDeviceState.value = onlineState(serialB)
        scope.runCurrent()

        controller.state.value.hostInput shouldBe "proxy.acme.dev"
        controller.state.value.recents.size shouldBe 1
        controller.state.value.serial shouldBe serialB
        controller.state.value.readState shouldBe ProxyReadState.Disabled
    }

    @Test
    fun `Use my computer IP fills the host field with the single resolved candidate`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val hostNetworkInfo = FakeHostNetworkInfo(
            enumeration = HostInterfaceEnumeration.Success(
                listOf(NetworkInterfaceCandidate("en0", "Wi-Fi", "10.0.4.117", isUp = true, isLoopback = false)),
            ),
        )
        val (scope, selectedDeviceState, controller) = harness(transport, hostNetworkInfo = hostNetworkInfo)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.UseComputerIp)
        scope.runCurrent()

        controller.state.value.hostInput shouldBe "10.0.4.117"
        controller.state.value.isResolvingIp shouldBe false
        controller.state.value.isBusy shouldBe false
        controller.state.value.error shouldBe null
    }

    @Test
    fun `Use my computer IP surfaces ambiguity as an actionable error without filling the field`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val hostNetworkInfo = FakeHostNetworkInfo(
            enumeration = HostInterfaceEnumeration.Success(
                listOf(
                    NetworkInterfaceCandidate("en0", "Wi-Fi", "10.0.4.117", isUp = true, isLoopback = false),
                    NetworkInterfaceCandidate("en1", "Ethernet", "10.0.4.200", isUp = true, isLoopback = false),
                ),
            ),
        )
        val (scope, selectedDeviceState, controller) = harness(transport, hostNetworkInfo = hostNetworkInfo)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.UseComputerIp)
        scope.runCurrent()

        controller.state.value.hostInput shouldBe ""
        controller.state.value.error shouldBe "Multiple network interfaces found — choose one manually"
        controller.state.value.isBusy shouldBe false
    }

    @Test
    fun `Use my computer IP surfaces discovery failure as an error`() = runTest {
        val transport = FakeAdbTransport(textScript = { completedText(":0") })
        val hostNetworkInfo = FakeHostNetworkInfo(enumeration = HostInterfaceEnumeration.Failed("permission denied"))
        val (scope, selectedDeviceState, controller) = harness(transport, hostNetworkInfo = hostNetworkInfo)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent()

        controller.handle(ProxyIntent.UseComputerIp)
        scope.runCurrent()

        controller.state.value.error shouldBe "permission denied"
        controller.state.value.isResolvingIp shouldBe false
        controller.state.value.isBusy shouldBe false
    }

    @Test
    fun `a device switch queued before a resolve is drained suppresses the stale IP result`() = runTest {
        // Mirrors "a device switch mid-apply suppresses the stale result for the old serial": the
        // initial read on A is in flight (delayed), so the ResolveIp item enqueued behind it is
        // still waiting in the channel when the device switch increments the generation — proving
        // the resolution itself is never even attempted for the stale generation.
        val transport = DelayedAdbTransport(
            listOf(
                DelayedResponse(delayMillis = 50, stdout = ":0"), // initial read on selecting A, in flight
                DelayedResponse(delayMillis = 0, stdout = ":0"), // read triggered by selecting B
            ),
        )
        val hostNetworkInfo = FakeHostNetworkInfo(
            enumeration = HostInterfaceEnumeration.Success(
                listOf(NetworkInterfaceCandidate("en0", "Wi-Fi", "10.0.4.117", isUp = true, isLoopback = false)),
            ),
        )
        val (scope, selectedDeviceState, controller) = harness(transport, hostNetworkInfo = hostNetworkInfo)
        selectedDeviceState.value = onlineState(serialA)
        scope.runCurrent() // the initial read(A) starts and suspends on its delay

        controller.handle(ProxyIntent.UseComputerIp) // queued behind the busy actor
        selectedDeviceState.value = onlineState(serialB) // switches before ResolveIp is drained
        scope.runCurrent()

        scope.advanceTimeBy(50)
        scope.runCurrent()

        controller.state.value.hostInput shouldBe ""
        controller.state.value.serial shouldBe serialB
        controller.state.value.readState shouldBe ProxyReadState.Disabled
        controller.state.value.isResolvingIp shouldBe false
    }
}

private data class DelayedResponse(val delayMillis: Long, val stdout: String)

/**
 * A deterministic [AdbTransport] double that suspends via [kotlinx.coroutines.delay] before
 * returning each scripted response in order — unlike [FakeAdbTransport], which never suspends —
 * so a test can drive a real race between an in-flight call and a `TestScope` virtual-time advance.
 */
private class DelayedAdbTransport(private val responses: List<DelayedResponse>) : AdbTransport {
    private var index = 0
    private val _calls = mutableListOf<AdbRequest>()
    val calls: List<AdbRequest> get() = _calls

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        _calls += request
        val response = responses[index++]
        if (response.delayMillis > 0) delay(response.delayMillis)
        return AdbTextResult(AdbOutcome.Completed(0), stdout = response.stdout, stderr = "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = emptyFlow()

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome = AdbOutcome.Completed(0)
}
