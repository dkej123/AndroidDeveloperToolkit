@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.devicecontext

import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.devicecontext.FakeOverrideReapplyPersistence
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetOutcome
import dev.acme.adbtoolbox.domain.devicecontext.OverrideResetUseCase
import dev.acme.adbtoolbox.domain.devicecontext.PendingReapplyOverride
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private class FakeOverrideResetUseCase(override val featureId: String) : OverrideResetUseCase {
    private val values = mutableMapOf<DeviceSerial, String>()
    var resetOutcome: OverrideResetOutcome = OverrideResetOutcome.Success
    var reapplyOutcome: OverrideResetOutcome = OverrideResetOutcome.Success
    var resetDelayMillis: Long = 0

    val resetCalls = mutableListOf<DeviceSerial>()
    val reapplyCalls = mutableListOf<Pair<DeviceSerial, String>>()

    fun setCurrent(serial: DeviceSerial, value: String?) {
        if (value == null) values.remove(serial) else values[serial] = value
    }

    override fun currentValue(serial: DeviceSerial): String? = values[serial]

    override suspend fun reset(serial: DeviceSerial): OverrideResetOutcome {
        resetCalls += serial
        if (resetDelayMillis > 0) delay(resetDelayMillis)
        if (resetOutcome is OverrideResetOutcome.Success) values.remove(serial)
        return resetOutcome
    }

    override suspend fun reapply(serial: DeviceSerial, value: String): OverrideResetOutcome {
        reapplyCalls += serial to value
        if (reapplyOutcome is OverrideResetOutcome.Success) values[serial] = value
        return reapplyOutcome
    }
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun onlineState(serial: DeviceSerial) =
    SelectedDeviceState.Online(Device(serial = serial, state = DeviceConnectionState.Online))

private fun disconnectedState(serial: DeviceSerial) = SelectedDeviceState.Disconnected(serial)

class OverrideResetCoordinatorTest {

    private fun harness(
        vararg useCases: FakeOverrideResetUseCase,
        selected: SelectedDeviceState = SelectedDeviceState.None,
        reapplyPersistence: FakeOverrideReapplyPersistence = FakeOverrideReapplyPersistence(),
    ): TestHarness {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val dispatchers = TestDispatcherProviderFixture(dispatcher)
        val feedback = FeedbackViewModel(scope = scope, dispatchers = dispatchers)
        val selectedDeviceState = MutableStateFlow(selected)
        val coordinator = OverrideResetCoordinator(
            scope = scope,
            dispatchers = dispatchers,
            selectedDeviceState = selectedDeviceState,
            resetUseCases = useCases.toList(),
            reapplyPersistence = reapplyPersistence,
            feedback = feedback,
        )
        return TestHarness(scope, selectedDeviceState, coordinator, feedback)
    }

    private data class TestHarness(
        val scope: TestScope,
        val selectedDeviceState: MutableStateFlow<SelectedDeviceState>,
        val coordinator: OverrideResetCoordinator,
        val feedback: FeedbackViewModel,
    )

    @Test
    fun `resetAll with zero overrides is a no-op`() = with(harness(
        FakeOverrideResetUseCase("font-scale"),
        selected = onlineState(serialA),
    )) {
        scope.runCurrent()

        coordinator.resetAll()
        scope.runCurrent()

        feedback.state.value.toasts shouldBe emptyList()
    }

    @Test
    fun `resetAll resets every feature with an applied override for the exact selected serial`() {
        val fontScale = FakeOverrideResetUseCase("font-scale").apply { setCurrent(serialA, "1.3") }
        val density = FakeOverrideResetUseCase("density").apply { setCurrent(serialA, "160") }
        val proxy = FakeOverrideResetUseCase("proxy").apply {
            setCurrent(serialA, "10.0.0.1:8080")
            setCurrent(serialB, "10.0.0.2:9090")
        }
        with(harness(fontScale, density, proxy, selected = onlineState(serialA))) {
            scope.runCurrent()

            coordinator.resetAll()
            scope.runCurrent()

            fontScale.resetCalls shouldBe listOf(serialA)
            density.resetCalls shouldBe listOf(serialA)
            proxy.resetCalls shouldBe listOf(serialA)
            feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Success
        }
    }

    @Test
    fun `a partial reset failure is reported without hiding the features that succeeded`() {
        val fontScale = FakeOverrideResetUseCase("font-scale").apply {
            setCurrent(serialA, "1.3")
            resetOutcome = OverrideResetOutcome.Failed("device offline")
        }
        val density = FakeOverrideResetUseCase("density").apply { setCurrent(serialA, "160") }
        with(harness(fontScale, density, selected = onlineState(serialA))) {
            scope.runCurrent()

            coordinator.resetAll()
            scope.runCurrent()

            fontScale.resetCalls shouldBe listOf(serialA)
            density.resetCalls shouldBe listOf(serialA)
            feedback.state.value.toasts.single().severity shouldBe FeedbackSeverity.Error
        }
    }

    @Test
    fun `cancelling the owning scope stops any remaining feature resets`() {
        val fontScale = FakeOverrideResetUseCase("font-scale").apply {
            setCurrent(serialA, "1.3")
            resetDelayMillis = 100
        }
        val density = FakeOverrideResetUseCase("density").apply { setCurrent(serialA, "160") }
        with(harness(fontScale, density, selected = onlineState(serialA))) {
            scope.runCurrent()

            coordinator.resetAll()
            scope.runCurrent()
            scope.cancel()
            scope.advanceTimeBy(200)

            fontScale.resetCalls shouldBe listOf(serialA)
            density.resetCalls shouldBe emptyList()
        }
    }

    @Test
    fun `a device switch mid reset-all never lets a later feature reset land on the new serial`() {
        val fontScale = FakeOverrideResetUseCase("font-scale").apply {
            setCurrent(serialA, "1.3")
            resetDelayMillis = 100
        }
        val density = FakeOverrideResetUseCase("density").apply {
            setCurrent(serialA, "160")
            setCurrent(serialB, "240")
        }
        with(harness(fontScale, density, selected = onlineState(serialA))) {
            scope.runCurrent()

            coordinator.resetAll()
            scope.runCurrent()
            selectedDeviceState.value = onlineState(serialB)
            scope.advanceTimeBy(200)
            scope.runCurrent()

            fontScale.resetCalls shouldBe listOf(serialA)
            density.resetCalls shouldBe emptyList()
        }
    }

    @Test
    fun `a device going offline captures its currently applied overrides as a pending re-apply, without touching the device`() {
        val fontScale = FakeOverrideResetUseCase("font-scale").apply { setCurrent(serialA, "1.3") }
        val persistence = FakeOverrideReapplyPersistence()
        with(harness(fontScale, selected = onlineState(serialA), reapplyPersistence = persistence)) {
            scope.runCurrent()

            selectedDeviceState.value = disconnectedState(serialA)
            scope.runCurrent()

            coordinator.pendingReapply.value shouldBe mapOf(serialA to listOf(PendingReapplyOverride("font-scale", "1.3")))
            persistence.writes.last() shouldBe mapOf(serialA to listOf(PendingReapplyOverride("font-scale", "1.3")))
            fontScale.reapplyCalls shouldBe emptyList()
        }
    }

    @Test
    fun `reconnecting a serial with a pending re-apply offers it without applying anything automatically`() {
        val fontScale = FakeOverrideResetUseCase("font-scale")
        val persistence = FakeOverrideReapplyPersistence(
            initial = mapOf(serialA to listOf(PendingReapplyOverride("font-scale", "1.3"))),
        )
        with(harness(fontScale, selected = disconnectedState(serialA), reapplyPersistence = persistence)) {
            scope.runCurrent()

            selectedDeviceState.value = onlineState(serialA)
            scope.runCurrent()

            val toast = feedback.state.value.toasts.single()
            toast.severity shouldBe FeedbackSeverity.Warning
            fontScale.reapplyCalls shouldBe emptyList()
            coordinator.pendingReapply.value shouldBe mapOf(serialA to listOf(PendingReapplyOverride("font-scale", "1.3")))
        }
    }

    @Test
    fun `accepting a pending re-apply delegates to the matching feature and clears the pending state`() {
        val fontScale = FakeOverrideResetUseCase("font-scale")
        val persistence = FakeOverrideReapplyPersistence(
            initial = mapOf(serialA to listOf(PendingReapplyOverride("font-scale", "1.3"))),
        )
        with(harness(fontScale, selected = onlineState(serialA), reapplyPersistence = persistence)) {
            scope.runCurrent()
            coordinator.pendingReapply.value shouldBe mapOf(serialA to listOf(PendingReapplyOverride("font-scale", "1.3")))

            coordinator.acceptReapply(serialA)
            scope.runCurrent()

            fontScale.reapplyCalls shouldBe listOf(serialA to "1.3")
            coordinator.pendingReapply.value shouldBe emptyMap()
            persistence.writes.last() shouldBe emptyMap()
            feedback.state.value.toasts.any { it.severity == FeedbackSeverity.Success } shouldBe true
        }
    }

    @Test
    fun `declining a pending re-apply clears it without invoking any feature`() {
        val fontScale = FakeOverrideResetUseCase("font-scale")
        val persistence = FakeOverrideReapplyPersistence(
            initial = mapOf(serialA to listOf(PendingReapplyOverride("font-scale", "1.3"))),
        )
        with(harness(fontScale, selected = onlineState(serialA), reapplyPersistence = persistence)) {
            scope.runCurrent()

            coordinator.declineReapply(serialA)
            scope.runCurrent()

            fontScale.reapplyCalls shouldBe emptyList()
            coordinator.pendingReapply.value shouldBe emptyMap()
            persistence.writes.last() shouldBe emptyMap()
        }
    }

    @Test
    fun `accepting a re-apply that is no longer pending (already declined) is a safe no-op`() {
        val fontScale = FakeOverrideResetUseCase("font-scale")
        with(harness(fontScale, selected = onlineState(serialA))) {
            scope.runCurrent()

            coordinator.acceptReapply(serialA)
            scope.runCurrent()

            fontScale.reapplyCalls shouldBe emptyList()
        }
    }
}
