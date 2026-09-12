@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.devicefacts

import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactId
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private val serial = DeviceSerial.of("R58N90ABCDE")

class LoadDeviceFactsUseCaseTest {

    @Test
    fun `every request targets the explicit serial, never an implicit current device`() = runTest {
        val transport = FakeAdbTransport(textScript = { AdbTextResult(AdbOutcome.Completed(0), stdout = "irrelevant", stderr = "") })
        val useCase = LoadDeviceFactsUseCase(transport)

        useCase.execute(serial).toList()

        transport.textRequests.forEach { request ->
            (request as AdbDeviceRequest).serial shouldBe serial
        }
        transport.textRequests.size shouldBe DeviceFactId.entries.size
    }

    @Test
    fun `all six facts resolve independently for well-formed output`() = runTest {
        val transport = FakeAdbTransport(textScript = { request -> textResultFor(request) })
        val useCase = LoadDeviceFactsUseCase(transport)

        val results = useCase.execute(serial).toList().toMap()

        results[DeviceFactId.AndroidVersion] shouldBe DeviceFactState.Available(DeviceFactValue.AndroidVersion("15", 35))
        results[DeviceFactId.Resolution] shouldBe DeviceFactState.Available(DeviceFactValue.Resolution(1080, 2400))
        results[DeviceFactId.Density] shouldBe DeviceFactState.Available(DeviceFactValue.Density(420))
        results[DeviceFactId.Battery] shouldBe DeviceFactState.Available(DeviceFactValue.Battery(72, charging = true))
        results[DeviceFactId.Abi] shouldBe DeviceFactState.Available(DeviceFactValue.Abi("arm64-v8a"))
        results[DeviceFactId.Uptime]!!.shouldBeInstanceOf<DeviceFactState.Available>()
    }

    @Test
    fun `one fact's malformed output does not affect the others (partial success)`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                if (command == "dumpsys battery") {
                    AdbTextResult(AdbOutcome.Completed(0), stdout = "garbage, not battery output", stderr = "")
                } else {
                    textResultFor(request)
                }
            },
        )
        val useCase = LoadDeviceFactsUseCase(transport)

        val results = useCase.execute(serial).toList().toMap()

        results[DeviceFactId.Battery].shouldBeInstanceOf<DeviceFactState.Unavailable>()
        results[DeviceFactId.AndroidVersion] shouldBe DeviceFactState.Available(DeviceFactValue.AndroidVersion("15", 35))
        results[DeviceFactId.Abi] shouldBe DeviceFactState.Available(DeviceFactValue.Abi("arm64-v8a"))
    }

    @Test
    fun `a transport failure on one fact surfaces as Unavailable, not a crash`() = runTest {
        val transport = FakeAdbTransport(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                if (command == "uptime") {
                    AdbTextResult(AdbOutcome.TransportFailure("device offline"), stdout = "", stderr = "")
                } else {
                    textResultFor(request)
                }
            },
        )
        val useCase = LoadDeviceFactsUseCase(transport)

        val results = useCase.execute(serial).toList().toMap()

        results[DeviceFactId.Uptime] shouldBe DeviceFactState.Unavailable("device offline")
    }

    private fun textResultFor(request: dev.acme.adbtoolbox.domain.adb.AdbRequest): AdbTextResult {
        val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
        val stdout = when (command) {
            "getprop ro.build.version.release ; getprop ro.build.version.sdk" -> "15\n35\n"
            "wm size" -> "Physical size: 1080x2400\n"
            "wm density" -> "Physical density: 420\n"
            "dumpsys battery" -> "level: 72\nscale: 100\nstatus: 2\n"
            "getprop ro.product.cpu.abi" -> "arm64-v8a\n"
            "uptime" -> " 14:32:01 up 4:12,  0 users,  load average: 0.10, 0.05, 0.01\n"
            else -> error("unexpected command: $command")
        }
        return AdbTextResult(AdbOutcome.Completed(0), stdout = stdout, stderr = "")
    }
}
