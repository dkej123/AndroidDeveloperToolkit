@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.*
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

class DeviceDeepLinkControllerTest {
    private class Memory : DeviceDeepLinkMemory {
        override var lastPackage = ""
        override fun entries() = emptyList<RecentDeviceLink>()
        override fun replace(entries: List<RecentDeviceLink>) {}
    }
    @Test
    fun `transport exceptions restore the button and clearing removes the error`() = runTest {
        val controller = DeviceDeepLinkController(backgroundScope, DeviceDeepLinkUseCase(
            FakeAdbTransport(textScript = { throw IllegalStateException("Device disconnected") }), Memory(),
        ) { 0 }, DeviceSerial.of("s"))
        controller.open("https://example.com", "")
        runCurrent()
        (controller.state.value.result as DeviceLinkResult.Failed).message shouldBe "Device disconnected"
        controller.clear()
        controller.state.value shouldBe DeviceLinkLaunchState()
    }

    @Test
    fun `a failed open restores the button and permits retrying the same URI`() = runTest {
        var response = AdbTextResult(AdbOutcome.Completed(1), "", "Open failed")
        val controller = DeviceDeepLinkController(backgroundScope, DeviceDeepLinkUseCase(FakeAdbTransport(textScript = { response }), Memory()) { 0 }, DeviceSerial.of("s"))
        controller.open("geo:52.2,21.0", "")
        runCurrent()
        controller.state.value.busy shouldBe false
        (controller.state.value.result as DeviceLinkResult.Failed).message shouldBe "Open failed"
        response = AdbTextResult(AdbOutcome.Completed(0), "Status: ok", "")
        controller.open("geo:52.2,21.0", "")
        runCurrent()
        controller.state.value.busy shouldBe false
        controller.state.value.opened?.uri shouldBe "geo:52.2,21.0"
        (controller.state.value.result is DeviceLinkResult.Opened) shouldBe true
    }
    @Test
    fun `Clear immediately releases a pending request and a late result cannot change the cleared form`() = runTest {
        val transport = object : AdbTransport by FakeAdbTransport() {
            override suspend fun executeText(request: AdbRequest): AdbTextResult = awaitCancellation()
        }
        val controller = DeviceDeepLinkController(backgroundScope, DeviceDeepLinkUseCase(transport, Memory()) { 0 }, DeviceSerial.of("s"))
        controller.open("test://item", "")
        runCurrent()
        controller.state.value.busy shouldBe true
        controller.clear()
        controller.state.value shouldBe DeviceLinkLaunchState()
        runCurrent()
        controller.state.value shouldBe DeviceLinkLaunchState()
    }
}
