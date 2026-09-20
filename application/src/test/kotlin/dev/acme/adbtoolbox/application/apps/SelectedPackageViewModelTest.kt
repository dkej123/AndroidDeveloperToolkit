@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.apps

import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.apps.FakeSelectedPackagePersistence
import dev.acme.adbtoolbox.domain.apps.SelectedPackage
import dev.acme.adbtoolbox.domain.apps.SelectedPackageState
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
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

class SelectedPackageViewModelTest {

    private fun harness(
        persistence: FakeSelectedPackagePersistence = FakeSelectedPackagePersistence(),
    ): Pair<TestScope, SelectedPackageViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val viewModel = SelectedPackageViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            persistence = persistence,
        )
        return scope to viewModel
    }

    @Test
    fun `initial state is Loading before persistence restore resolves`() {
        val (_, viewModel) = harness()

        viewModel.state.value shouldBe SelectedPackageState.Loading
    }

    @Test
    fun `with no persisted selection, state settles to None`() = runTest {
        val (scope, viewModel) = harness()
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.None
    }

    @Test
    fun `a persisted selection is restored on startup`() = runTest {
        val persistence = FakeSelectedPackagePersistence(initial = SelectedPackage(serialA, "com.acme.shop"))
        val (scope, viewModel) = harness(persistence)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.Selected(SelectedPackage(serialA, "com.acme.shop"))
    }

    @Test
    fun `Select intent updates state and persists the exact serial and package name`() = runTest {
        val persistence = FakeSelectedPackagePersistence()
        val (scope, viewModel) = harness(persistence)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(SelectedPackageIntent.Select(serialA, "com.acme.shop"))
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.Selected(SelectedPackage(serialA, "com.acme.shop"))
        persistence.writes.last() shouldBe SelectedPackage(serialA, "com.acme.shop")
    }

    @Test
    fun `Clear intent produces None and persists null`() = runTest {
        val persistence = FakeSelectedPackagePersistence(initial = SelectedPackage(serialA, "com.acme.shop"))
        val (scope, viewModel) = harness(persistence)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(SelectedPackageIntent.Clear)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.None
        persistence.writes.last() shouldBe null
    }

    @Test
    fun `selecting a different serial's package overwrites the prior selection entirely`() = runTest {
        val (scope, viewModel) = harness()
        scope.advanceTimeBy(1)
        scope.runCurrent()
        viewModel.handle(SelectedPackageIntent.Select(serialA, "com.acme.shop"))
        scope.runCurrent()

        viewModel.handle(SelectedPackageIntent.Select(serialB, "com.acme.other"))
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.Selected(SelectedPackage(serialB, "com.acme.other"))
    }

    @Test
    fun `an explicit Select handled before restore resolves is not clobbered by the persisted value`() = runTest {
        val persistence = FakeSelectedPackagePersistence(initial = SelectedPackage(serialA, "com.acme.shop"))
        val (scope, viewModel) = harness(persistence)

        // Select is handled before the scope has run any pending coroutines, so restore()'s
        // launch (scheduled in init) is still pending when this explicit intent lands.
        viewModel.handle(SelectedPackageIntent.Select(serialB, "com.acme.other"))
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.Selected(SelectedPackage(serialB, "com.acme.other"))
    }

    @Test
    fun `an explicit Clear handled before restore resolves is not clobbered by the persisted value`() = runTest {
        val persistence = FakeSelectedPackagePersistence(initial = SelectedPackage(serialA, "com.acme.shop"))
        val (scope, viewModel) = harness(persistence)

        viewModel.handle(SelectedPackageIntent.Clear)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.None
    }

    @Test
    fun `an unexpected persistence read failure degrades to None, never a crash`() = runTest {
        val persistence = FakeSelectedPackagePersistence().apply { readFailure = RuntimeException("disk exploded") }
        val (scope, viewModel) = harness(persistence)

        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe SelectedPackageState.None
    }
}
