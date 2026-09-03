@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.nav

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.FakeNavigationPersistence
import dev.acme.adbtoolbox.domain.nav.NavigationState
import dev.acme.adbtoolbox.domain.nav.ViewId
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

class NavigationViewModelTest {

    private fun harness(
        persistence: FakeNavigationPersistence = FakeNavigationPersistence(),
        defaultViewId: ViewId = ViewId.Device,
    ): Pair<TestScope, NavigationViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val viewModel = NavigationViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            persistence = persistence,
            defaultViewId = defaultViewId,
        )
        return scope to viewModel
    }

    @Test
    fun `initial state is Loading before persistence restore resolves`() {
        val (_, viewModel) = harness()

        viewModel.state.value shouldBe NavigationState.Loading
    }

    @Test
    fun `with no persisted view, state settles to the caller-supplied default`() = runTest {
        val (scope, viewModel) = harness(defaultViewId = ViewId.Device)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe NavigationState.Ready(ViewId.Device)
    }

    @Test
    fun `a persisted view restores as the selected destination`() = runTest {
        val (scope, viewModel) = harness(persistence = FakeNavigationPersistence(initial = ViewId.Logcat))
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value shouldBe NavigationState.Ready(ViewId.Logcat)
    }

    @Test
    fun `every destination is selectable via explicit intent`() = runTest {
        val (scope, viewModel) = harness()
        scope.advanceTimeBy(1)
        scope.runCurrent()

        ViewId.entries.forEach { viewId ->
            viewModel.handle(NavigationIntent.Select(viewId))
            scope.runCurrent()

            viewModel.state.value shouldBe NavigationState.Ready(viewId)
        }
    }

    @Test
    fun `selecting a view persists it`() = runTest {
        val persistence = FakeNavigationPersistence()
        val (scope, viewModel) = harness(persistence = persistence)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(NavigationIntent.Select(ViewId.Network))
        scope.runCurrent()

        persistence.writes shouldBe listOf(ViewId.Network)
    }

    @Test
    fun `a persistence read failure surfaces as a recoverable Error, not a crash`() = runTest {
        val persistence = FakeNavigationPersistence()
        persistence.readFailure = IllegalStateException("boom")
        val (scope, viewModel) = harness(persistence = persistence)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.state.value.shouldBeInstanceOf<NavigationState.Error>()
    }

    @Test
    fun `RetryRestore after a read failure resolves normally once persistence recovers`() = runTest {
        val persistence = FakeNavigationPersistence(initial = ViewId.Apps)
        persistence.readFailure = IllegalStateException("boom")
        val (scope, viewModel) = harness(persistence = persistence)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        viewModel.state.value.shouldBeInstanceOf<NavigationState.Error>()

        viewModel.handle(NavigationIntent.RetryRestore)
        scope.runCurrent()

        viewModel.state.value shouldBe NavigationState.Ready(ViewId.Apps)
    }

    @Test
    fun `an in-flight write is superseded by a newer selection, never persisting a stale value last`() = runTest {
        val persistence = FakeNavigationPersistence(writeDelayMillis = 100)
        val (scope, viewModel) = harness(persistence = persistence)
        scope.advanceTimeBy(1)
        scope.runCurrent()

        viewModel.handle(NavigationIntent.Select(ViewId.Display))
        scope.runCurrent()
        viewModel.handle(NavigationIntent.Select(ViewId.Settings))
        scope.advanceTimeBy(200)
        scope.runCurrent()

        persistence.writes.last() shouldBe ViewId.Settings
    }
}
