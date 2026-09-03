@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.device

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.device.Device
import dev.acme.adbtoolbox.domain.device.DeviceConnectionState
import dev.acme.adbtoolbox.domain.device.DeviceCommandContext
import dev.acme.adbtoolbox.domain.device.FakeDeviceRepository
import dev.acme.adbtoolbox.domain.device.FakeDeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.device.SelectedDeviceState
import dev.acme.adbtoolbox.domain.device.toCommandContext
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private val serialA = DeviceSerial.of("AAAA111")
private val serialB = DeviceSerial.of("BBBB222")

private fun device(serial: DeviceSerial, state: DeviceConnectionState = DeviceConnectionState.Online) =
    Device(serial = serial, state = state)

class SelectedDeviceViewModelTest {

    private fun harness(
        persistence: FakeDeviceSelectionPersistence = FakeDeviceSelectionPersistence(),
        repository: FakeDeviceRepository = FakeDeviceRepository(),
    ): Triple<TestScope, FakeDeviceRepository, SelectedDeviceViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val viewModel = SelectedDeviceViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            deviceRepository = repository,
            persistence = persistence,
        )
        return Triple(scope, repository, viewModel)
    }

    @Test
    fun `initial state is Loading before persistence restore resolves`() {
        val (_, _, viewModel) = harness()

        viewModel.state.value shouldBe SelectedDeviceState.Loading
    }

    @Test
    fun `with no persisted serial and multiple devices present, state settles to None, never auto-selecting the first device`() =
        runTest {
            val repository = FakeDeviceRepository(listOf(device(serialA), device(serialB)))
            val (scope, _, viewModel) = harness(repository = repository)
            scope.advanceTimeBy(1)
            scope.runCurrent()

            viewModel.state.value shouldBe SelectedDeviceState.None
        }

    @Test
    fun `explicit user selection of an online device produces Online`() = runTest {
        val repository = FakeDeviceRepository(listOf(device(serialA), device(serialB)))
        val (scope, _, viewModel) = harness(repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(SelectedDeviceIntent.Select(serialB))
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.Online(device(serialB))
    }

    @Test
    fun `selecting an unauthorized device reflects Unauthorized, never coerced to Online`() = runTest {
        val repository = FakeDeviceRepository(listOf(device(serialA, DeviceConnectionState.Unauthorized)))
        val (scope, _, viewModel) = harness(repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(SelectedDeviceIntent.Select(serialA))
        scope.runCurrent()

        val state = viewModel.state.value
        state shouldBe SelectedDeviceState.Unauthorized(device(serialA, DeviceConnectionState.Unauthorized))
        state.toCommandContext().shouldBeInstanceOf<DeviceCommandContext.Disabled>()
    }

    @Test
    fun `selecting an offline device reflects Offline, never coerced to Online`() = runTest {
        val repository = FakeDeviceRepository(listOf(device(serialA, DeviceConnectionState.Offline)))
        val (scope, _, viewModel) = harness(repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(SelectedDeviceIntent.Select(serialA))
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.Offline(device(serialA, DeviceConnectionState.Offline))
    }

    @Test
    fun `removal of the selected device produces Disconnected, never silently switching to another device`() =
        runTest {
            val repository = FakeDeviceRepository(listOf(device(serialA), device(serialB)))
            val (scope, repo, viewModel) = harness(repository = repository)
            scope.advanceTimeBy(1)
            scope.runCurrent()
            viewModel.handle(SelectedDeviceIntent.Select(serialA))
            scope.runCurrent()

            repo.emit(listOf(device(serialB)))
            scope.runCurrent()

            viewModel.state.value shouldBe SelectedDeviceState.Disconnected(serialA)
        }

    @Test
    fun `reconnect of the same serial resolves back to Online`() = runTest {
        val repository = FakeDeviceRepository(listOf(device(serialA)))
        val (scope, repo, viewModel) = harness(repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        viewModel.handle(SelectedDeviceIntent.Select(serialA))
        scope.runCurrent()

        repo.emit(emptyList())
        scope.runCurrent()
        viewModel.state.value shouldBe SelectedDeviceState.Disconnected(serialA)

        repo.emit(listOf(device(serialA)))
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.Online(device(serialA))
    }

    @Test
    fun `a device list refresh with the selected device still present keeps the selection`() = runTest {
        val repository = FakeDeviceRepository(listOf(device(serialA)))
        val (scope, repo, viewModel) = harness(repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        viewModel.handle(SelectedDeviceIntent.Select(serialA))
        scope.runCurrent()

        repo.emit(listOf(device(serialA), device(serialB)))
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.Online(device(serialA))
    }

    @Test
    fun `ClearSelection intent produces None and is persisted`() = runTest {
        val persistence = FakeDeviceSelectionPersistence()
        val repository = FakeDeviceRepository(listOf(device(serialA)))
        val (scope, _, viewModel) = harness(persistence = persistence, repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        viewModel.handle(SelectedDeviceIntent.Select(serialA))
        scope.runCurrent()

        viewModel.handle(SelectedDeviceIntent.ClearSelection)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.None
        persistence.writeCompletions.last() shouldBe null
    }

    @Test
    fun `on startup, a persisted serial that is currently online is restored`() = runTest {
        val persistence = FakeDeviceSelectionPersistence(initial = serialA)
        val repository = FakeDeviceRepository(listOf(device(serialA)))
        val (scope, _, viewModel) = harness(persistence = persistence, repository = repository)

        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.Online(device(serialA))
    }

    @Test
    fun `on startup, a persisted serial not present in the live list restores as Disconnected, never substituted`() =
        runTest {
            val persistence = FakeDeviceSelectionPersistence(initial = serialA)
            val repository = FakeDeviceRepository(listOf(device(serialB)))
            val (scope, _, viewModel) = harness(persistence = persistence, repository = repository)

            scope.advanceTimeBy(1)
            scope.runCurrent()

            viewModel.state.value shouldBe SelectedDeviceState.Disconnected(serialA)
        }

    @Test
    fun `on startup with no persisted serial, state is None, never auto-selected`() = runTest {
        val repository = FakeDeviceRepository(listOf(device(serialA)))
        val (scope, _, viewModel) = harness(repository = repository)

        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.None
    }

    @Test
    fun `a stale in-flight persistence write is never allowed to clobber a newer selection`() = runTest {
        val persistence = FakeDeviceSelectionPersistence(writeDelayMillis = 100)
        val repository = FakeDeviceRepository(listOf(device(serialA), device(serialB)))
        val (scope, _, viewModel) = harness(persistence = persistence, repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(SelectedDeviceIntent.Select(serialA))
        scope.runCurrent()
        viewModel.handle(SelectedDeviceIntent.Select(serialB))
        scope.advanceTimeBy(200)
        scope.runCurrent()

        // Both selects were issued, but only the most recent write may ever complete/persist.
        persistence.writeCompletions shouldBe listOf(serialB)
        viewModel.state.value shouldBe SelectedDeviceState.Online(device(serialB))
    }

    @Test
    fun `an unexpected persistence read failure surfaces as a recoverable Error state`() = runTest {
        val persistence = FakeDeviceSelectionPersistence().apply { readFailure = RuntimeException("disk exploded") }
        val (scope, _, viewModel) = harness(persistence = persistence)

        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.Error("disk exploded")
    }

    @Test
    fun `RetryRestore recovers from an Error state once the persistence read succeeds`() = runTest {
        val persistence = FakeDeviceSelectionPersistence(initial = serialA).apply {
            readFailure = RuntimeException("disk exploded")
        }
        val repository = FakeDeviceRepository(listOf(device(serialA)))
        val (scope, _, viewModel) = harness(persistence = persistence, repository = repository)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        viewModel.state.value shouldBe SelectedDeviceState.Error("disk exploded")

        viewModel.handle(SelectedDeviceIntent.RetryRestore)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedDeviceState.Online(device(serialA))
    }
}
