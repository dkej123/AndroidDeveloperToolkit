@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.wifi

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbServerRequest
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.device.FakeDeviceListRefresher
import dev.acme.adbtoolbox.domain.wifi.PairingCode
import dev.acme.adbtoolbox.domain.wifi.PairingCodeResult
import dev.acme.adbtoolbox.domain.wifi.WifiEndpoint
import dev.acme.adbtoolbox.domain.wifi.WifiEndpointResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private fun endpoint(raw: String): WifiEndpoint = (WifiEndpoint.parse(raw) as WifiEndpointResult.Valid).endpoint
private fun code(raw: String): PairingCode = (PairingCode.parse(raw) as PairingCodeResult.Valid).code

/**
 * [WifiPairingUseCase] orchestrates task 039's pair-then-connect sequence entirely against
 * [FakeAdbTransport] (no real ADB/hardware needed) and only refreshes discovery once both stages
 * succeed.
 */
class WifiPairingUseCaseTest {

    private val pairingEndpoint = endpoint("192.168.1.42:37000")
    private val connectEndpoint = endpoint("192.168.1.42:5555")
    private val pairingCode = code("123456")

    @Test
    fun `a successful pair and connect refreshes discovery exactly once and reports Success`() = runTest {
        val refresher = FakeDeviceListRefresher()
        val transport = FakeAdbTransport(
            textScript = { request ->
                val args = (request as AdbServerRequest).arguments
                when (args.first()) {
                    "pair" -> AdbTextResult(AdbOutcome.Completed(0), "Successfully paired to 192.168.1.42:37000", "")
                    "connect" -> AdbTextResult(AdbOutcome.Completed(0), "connected to 192.168.1.42:5555", "")
                    else -> error("unexpected argv $args")
                }
            },
        )
        val useCase = WifiPairingUseCase(transport, refresher)

        val outcomeDeferred = async { useCase.pairAndConnect(pairingEndpoint, pairingCode, connectEndpoint) }
        runCurrent()
        refresher.complete()

        outcomeDeferred.await() shouldBe WifiPairingOutcome.Success
        refresher.callCount shouldBe 1
    }

    @Test
    fun `a failed pair stage never issues a connect call and never refreshes`() = runTest {
        val refresher = FakeDeviceListRefresher()
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(1), "", "Failed: Wrong pairing code") },
        )
        val useCase = WifiPairingUseCase(transport, refresher)

        val outcome = useCase.pairAndConnect(pairingEndpoint, pairingCode, connectEndpoint)

        outcome.shouldBeInstanceOf<WifiPairingOutcome.PairFailed>()
        transport.textRequests.map { (it as AdbServerRequest).arguments.first() } shouldBe listOf("pair")
        refresher.callCount shouldBe 0
    }

    @Test
    fun `a successful pair followed by a failed connect never refreshes`() = runTest {
        val refresher = FakeDeviceListRefresher()
        val transport = FakeAdbTransport(
            textScript = { request ->
                when ((request as AdbServerRequest).arguments.first()) {
                    "pair" -> AdbTextResult(AdbOutcome.Completed(0), "Successfully paired", "")
                    else -> AdbTextResult(AdbOutcome.Completed(1), "", "failed to connect")
                }
            },
        )
        val useCase = WifiPairingUseCase(transport, refresher)

        val outcome = useCase.pairAndConnect(pairingEndpoint, pairingCode, connectEndpoint)

        outcome.shouldBeInstanceOf<WifiPairingOutcome.ConnectFailed>()
        refresher.callCount shouldBe 0
    }

    @Test
    fun `a timed-out pair call reports TimedOut and issues no connect call`() = runTest {
        val refresher = FakeDeviceListRefresher()
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.TimedOut, "", "") })
        val useCase = WifiPairingUseCase(transport, refresher)

        val outcome = useCase.pairAndConnect(pairingEndpoint, pairingCode, connectEndpoint)

        outcome shouldBe WifiPairingOutcome.TimedOut
        transport.textRequests.size shouldBe 1
    }

    @Test
    fun `a cancelled pair call reports Cancelled and issues no connect call`() = runTest {
        val refresher = FakeDeviceListRefresher()
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Cancelled, "", "") })
        val useCase = WifiPairingUseCase(transport, refresher)

        val outcome = useCase.pairAndConnect(pairingEndpoint, pairingCode, connectEndpoint)

        outcome shouldBe WifiPairingOutcome.Cancelled
        transport.textRequests.size shouldBe 1
    }

    @Test
    fun `pair and connect requests carry the configured timeouts and never target a device serial`() = runTest {
        val refresher = FakeDeviceListRefresher()
        val transport = FakeAdbTransport(
            textScript = { request ->
                when ((request as AdbServerRequest).arguments.first()) {
                    "pair" -> AdbTextResult(AdbOutcome.Completed(0), "Successfully paired", "")
                    // Deliberately a failure so the use case never reaches its post-success
                    // refresh() call — this test only cares about the two requests' shapes.
                    else -> AdbTextResult(AdbOutcome.Completed(1), "", "failed to connect")
                }
            },
        )
        val useCase = WifiPairingUseCase(transport, refresher, pairTimeout = 3.seconds, connectTimeout = 4.seconds)

        useCase.pairAndConnect(pairingEndpoint, pairingCode, connectEndpoint)

        val requests: List<AdbRequest> = transport.textRequests
        requests.all { it is AdbServerRequest } shouldBe true
        requests[0].timeout shouldBe 3.seconds
        requests[1].timeout shouldBe 4.seconds
    }
}
