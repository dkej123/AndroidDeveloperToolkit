@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.shell

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: kotlinx.coroutines.CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

class ShellViewModelTest {

    @Test
    fun `initial state is ready and not refreshing`() {
        val scope = TestScope()
        val viewModel = ShellViewModel(scope, TestDispatcherProviderFixture(StandardTestDispatcher(scope.testScheduler)))

        viewModel.state.value shouldBe ShellViewState(statusMessage = "Ready", isRefreshing = false)
    }

    @Test
    fun `Refresh intent flips isRefreshing then settles back to Ready`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ShellViewModel(this, TestDispatcherProviderFixture(dispatcher))

        viewModel.handle(ShellIntent.Refresh)
        viewModel.state.value.isRefreshing shouldBe true

        advanceUntilIdle()

        viewModel.state.value shouldBe ShellViewState(statusMessage = "Ready", isRefreshing = false)
    }

    @Test
    fun `Refresh intent emits a ShowMessage effect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = ShellViewModel(this, TestDispatcherProviderFixture(dispatcher))

        viewModel.handle(ShellIntent.Refresh)
        advanceUntilIdle()

        viewModel.effects.first() shouldBe ShellEffect.ShowMessage("Refreshed")
    }
}
