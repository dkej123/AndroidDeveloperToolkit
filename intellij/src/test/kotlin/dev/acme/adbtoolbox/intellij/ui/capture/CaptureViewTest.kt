package dev.acme.adbtoolbox.intellij.ui.capture

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.capture.CaptureScreenshotUseCase
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.capture.CaptureViewState
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.capture.FakeCaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.ControlPolicy
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.intellij.ui.common.HeldDispatcher
import dev.acme.adbtoolbox.intellij.ui.common.HeldDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive

/**
 * Exercises [CaptureView.render] directly (constructed values, no real coroutine round trip through
 * this headless `:intellij:test` sandbox — the same pattern [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsCoordinatorTest]
 * documents) plus the real click -> [CaptureViewModel.handle] wiring.
 */
class CaptureViewTest : BasePlatformTestCase() {

    private class TestDispatchers : DispatcherProvider {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        // Like production: renders are queued on the EDT behind the test body, so a direct
        // render() in a test is never overwritten by a background initial-state render.
        override val main = Dispatchers.EDT
    }

    private fun onlineDevice(serial: String) = Device(serial = DeviceSerial.of(serial), state = DeviceConnectionState.Online)

    private fun viewModel(
        scope: CoroutineScope,
        dispatchers: DispatcherProvider,
        selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
    ): CaptureViewModel {
        val transport = FakeAdbTransport(
            binaryScript = { AdbBinaryScript(listOf(byteArrayOf(1, 2, 3)), AdbOutcome.Completed(0)) },
        )
        return CaptureViewModel(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            captureScreenshotUseCase = CaptureScreenshotUseCase(
                adbTransport = transport,
                captureDestination = FakeCaptureDestination(),
                fileNamePolicy = FileNamePolicy { "screen.png" },
            ),
            revealInFileManager = RevealInFileManager {},
            feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers),
        )
    }

    fun `test render enables the button only when the control policy is Enabled and no capture is in flight`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val vm = viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None))
        val view = CaptureView(vm, scope, dispatchers)

        view.render(CaptureViewState(controlPolicy = ControlPolicy.Enabled, isCapturing = false))
        assertTrue(view.screenshotButton.isEnabled)
        assertEquals("Screenshot", view.screenshotButton.accessibleContext.accessibleName)
        assertTrue(view.moreButton.isEnabled)
        assertTrue(view.fullScreenshotItem.isEnabled)
        assertEquals("Full page", view.fullScreenshotItem.text)

        view.render(CaptureViewState(controlPolicy = ControlPolicy.Enabled, isCapturing = true))
        assertFalse(view.screenshotButton.isEnabled)
        assertFalse(view.moreButton.isEnabled)
        assertFalse(view.fullScreenshotItem.isEnabled)

        view.dispose()
        assertFalse(scope.isActive)
    }

    fun `test the screenshot tooltip names the configured capture directory`() {
        val dispatchers = TestDispatchers()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = CaptureView(viewModel(scope, dispatchers, MutableStateFlow(SelectedDeviceState.None)), scope, dispatchers)
        view.render(CaptureViewState(controlPolicy = ControlPolicy.Enabled, isCapturing = false))

        view.setDestinationLabel("~/captures")

        assertEquals("Screenshot — save a PNG to ~/captures", view.screenshotButton.toolTipText)
        assertEquals(
            "Save the app's whole scrolling content as a PNG to ~/captures. " +
                "The app is briefly moved to a tall virtual screen, so its current screen is recreated.",
            view.fullScreenshotItem.toolTipText,
        )
        view.dispose()
    }

    fun `test clicking the button forwards a CaptureScreenshot request through the real view model`() {
        // CaptureViewModel.handle(CaptureScreenshot) marks isCapturing = true synchronously, before
        // ever launching the (async, IO-dispatched) capture itself — see CaptureViewModel's class doc
        // and CaptureViewModelTest's equivalent assertions. Asserting that synchronous transition is
        // enough to prove this button's click listener really forwards to viewModel.handle(...);
        // awaiting the async capture's own completion would instead exercise CaptureScreenshotUseCase
        // end to end (already covered thoroughly by CaptureScreenshotUseCaseTest/CaptureViewModelTest
        // at the application layer) through a live cross-dispatcher round trip this headless
        // `:intellij:test` sandbox does not reliably support (see CaptureView's own render loop, which
        // this test never exercises for the same reason — its scope is cancelled immediately).
        val dispatchers = TestDispatchers()
        // The view model's own coroutines are held, so only the click's synchronous effect is observed.
        val vmScope = CoroutineScope(SupervisorJob() + HeldDispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, HeldDispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = CaptureView(vm, viewScope, dispatchers)
        viewScope.cancel()

        assertFalse(vm.state.value.isCapturing)
        view.screenshotButton.doClick()

        assertTrue(vm.state.value.isCapturing)
        vmScope.cancel()
    }

    fun `test clicking Full page forwards a CaptureFullScreenshot request through the real view model`() {
        val dispatchers = TestDispatchers()
        // The view model's own coroutines are held, so only the click's synchronous effect is observed.
        val vmScope = CoroutineScope(SupervisorJob() + HeldDispatcher)
        val selectedDeviceState = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Online(onlineDevice("emulator-5554")))
        val vm = viewModel(vmScope, HeldDispatchers, selectedDeviceState)
        val viewScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val view = CaptureView(vm, viewScope, dispatchers)
        viewScope.cancel()

        view.fullScreenshotItem.doClick()

        assertTrue(vm.state.value.isCapturing)
        vmScope.cancel()
    }
}
