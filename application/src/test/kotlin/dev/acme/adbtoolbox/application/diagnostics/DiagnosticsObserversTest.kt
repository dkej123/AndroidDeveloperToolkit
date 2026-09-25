@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.diagnostics

import dev.acme.adbtoolbox.application.feedback.FeedbackViewState
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.diagnostics.DiagCategory
import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.RecordingDiagnosticsLog
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DiagnosticsObserversTest {

    private val serial = DeviceSerial.of("R58N90ABCDE")

    @Test
    fun `device list changes, list errors, selection changes and toasts are logged`() = runTest {
        val log = RecordingDiagnosticsLog()
        val repository = FakeDeviceRepository()
        val selected = MutableStateFlow<SelectedDeviceState>(SelectedDeviceState.Loading)
        val feedback = MutableStateFlow(FeedbackViewState())

        DiagnosticsObservers.start(backgroundScope, log, repository, selected, feedback)
        runCurrent()

        repository.emit(listOf(Device(serial = serial, state = DeviceConnectionState.Online, model = "Pixel_8")))
        repository.emitListError("adb executable not found")
        selected.value = SelectedDeviceState.None
        feedback.value = FeedbackViewState(
            toasts = listOf(FeedbackMessage("t1", "scrcpy exited unexpectedly (code 1)", FeedbackSeverity.Error)),
        )
        runCurrent()
        // The same toast staying on screen is not logged twice.
        feedback.value = feedback.value.copy()
        runCurrent()

        val devices = log.inCategory(DiagCategory.DEVICE)
        devices.any { it.message == "device list changed" && it.fields["devices"].toString().contains("R58N90ABCDE:Online:Pixel_8") } shouldBe true
        devices.single { it.message == "device list query failed" }.level shouldBe DiagLevel.WARN
        devices.any { it.message == "selection changed" && it.fields["state"].toString() == "None" } shouldBe true
        val toasts = log.inCategory(DiagCategory.FEEDBACK)
        toasts.size shouldBe 1
        toasts.single().level shouldBe DiagLevel.WARN
        toasts.single().fields["text"].toString() shouldContain "scrcpy exited"
    }
}
