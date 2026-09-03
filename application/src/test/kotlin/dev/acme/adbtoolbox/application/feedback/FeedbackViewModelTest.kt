@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.feedback

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.feedback.FeedbackAction
import dev.acme.adbtoolbox.domain.feedback.FeedbackMessage
import dev.acme.adbtoolbox.domain.feedback.FeedbackSeverity
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

private class TestDispatcherProviderFixture(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default = dispatcher
    override val io = dispatcher
    override val main = dispatcher
}

private fun message(
    id: String,
    text: String = id,
    severity: FeedbackSeverity = FeedbackSeverity.Info,
    action: FeedbackAction? = null,
) = FeedbackMessage(id = id, text = text, severity = severity, action = action)

class FeedbackViewModelTest {

    private fun harness(
        maxStack: Int = 3,
        autoDismissAfter: kotlin.time.Duration = 4000.milliseconds,
    ): Pair<TestScope, FeedbackViewModel> {
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val viewModel = FeedbackViewModel(
            scope = scope,
            dispatchers = TestDispatcherProviderFixture(dispatcher),
            maxStack = maxStack,
            autoDismissAfter = autoDismissAfter,
        )
        return scope to viewModel
    }

    @Test
    fun `initial state has no toasts and idle status`() {
        val (_, viewModel) = harness()

        viewModel.state.value shouldBe FeedbackViewState()
    }

    @Test
    fun `posted toasts are ordered oldest first`() = runTest {
        val (scope, viewModel) = harness()

        viewModel.handle(FeedbackIntent.Post(message("a")))
        viewModel.handle(FeedbackIntent.Post(message("b")))
        viewModel.handle(FeedbackIntent.Post(message("c")))
        scope.runCurrent()

        viewModel.state.value.toasts.map { it.id } shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `posting beyond the queue bound drops the oldest toast`() = runTest {
        val (scope, viewModel) = harness(maxStack = 3)

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Error)))
        viewModel.handle(FeedbackIntent.Post(message("b", severity = FeedbackSeverity.Error)))
        viewModel.handle(FeedbackIntent.Post(message("c", severity = FeedbackSeverity.Error)))
        viewModel.handle(FeedbackIntent.Post(message("d", severity = FeedbackSeverity.Error)))
        scope.runCurrent()

        viewModel.state.value.toasts.map { it.id } shouldBe listOf("b", "c", "d")
    }

    @Test
    fun `a success toast auto-dismisses after the configured duration using virtual time`() = runTest {
        val (scope, viewModel) = harness(autoDismissAfter = 4000.milliseconds)

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Success)))
        scope.runCurrent()
        viewModel.state.value.toasts.map { it.id } shouldBe listOf("a")

        scope.advanceTimeBy(3999)
        scope.runCurrent()
        viewModel.state.value.toasts.map { it.id } shouldBe listOf("a")

        scope.advanceTimeBy(2)
        scope.runCurrent()
        viewModel.state.value.toasts shouldBe emptyList()
    }

    @Test
    fun `an error toast never auto-expires even after a very large time advance`() = runTest {
        val (scope, viewModel) = harness(autoDismissAfter = 4000.milliseconds)

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Error)))
        scope.runCurrent()

        scope.advanceTimeBy(1_000_000_000)
        scope.runCurrent()

        viewModel.state.value.toasts.map { it.id } shouldBe listOf("a")
    }

    @Test
    fun `manual dismissal removes a toast of any severity`() = runTest {
        val (scope, viewModel) = harness()

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Error)))
        scope.runCurrent()

        viewModel.handle(FeedbackIntent.Dismiss("a"))
        scope.runCurrent()

        viewModel.state.value.toasts shouldBe emptyList()
    }

    @Test
    fun `invoking a toast's fix action runs it and dismisses the toast`() = runTest {
        val (scope, viewModel) = harness()
        var invoked = false
        val action = FeedbackAction("Retry") { invoked = true }

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Error, action = action)))
        scope.runCurrent()

        viewModel.handle(FeedbackIntent.InvokeAction("a"))
        scope.runCurrent()

        invoked shouldBe true
        viewModel.state.value.toasts shouldBe emptyList()
    }

    @Test
    fun `invoking an action on an unknown or actionless toast is a safe no-op`() = runTest {
        val (scope, viewModel) = harness()

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Info)))
        scope.runCurrent()

        viewModel.handle(FeedbackIntent.InvokeAction("a"))
        viewModel.handle(FeedbackIntent.InvokeAction("does-not-exist"))
        scope.runCurrent()

        // "a" had no action: InvokeAction is a no-op that leaves it in place, not a dismissal.
        viewModel.state.value.toasts.map { it.id } shouldBe listOf("a")
    }

    @Test
    fun `posting a toast mirrors its text into the persistent status message`() = runTest {
        val (scope, viewModel) = harness()

        viewModel.handle(FeedbackIntent.Post(message("a", text = "First")))
        scope.runCurrent()
        viewModel.state.value.status.message shouldBe "First"

        viewModel.handle(FeedbackIntent.Post(message("b", text = "Second")))
        scope.runCurrent()
        viewModel.state.value.status.message shouldBe "Second"

        // Status message survives the toast's own dismissal/expiry (last-posted, not "live toast").
        viewModel.handle(FeedbackIntent.Dismiss("b"))
        scope.runCurrent()
        viewModel.state.value.status.message shouldBe "Second"
    }

    @Test
    fun `an in-progress process indicator can be shown and cleared`() = runTest {
        val (scope, viewModel) = harness()

        viewModel.handle(FeedbackIntent.SetProcess(ProcessIndicator.InProgress("scrcpy")))
        scope.runCurrent()
        viewModel.state.value.status.process shouldBe ProcessIndicator.InProgress("scrcpy")

        viewModel.handle(FeedbackIntent.SetProcess(ProcessIndicator.Idle))
        scope.runCurrent()
        viewModel.state.value.status.process shouldBe ProcessIndicator.Idle
    }

    @Test
    fun `after disposal, posting a new message is a safe no-op`() = runTest {
        val (scope, viewModel) = harness()
        viewModel.dispose()

        viewModel.handle(FeedbackIntent.Post(message("a")))
        scope.runCurrent()

        viewModel.state.value shouldBe FeedbackViewState()
    }

    @Test
    fun `a pending expiry timer does not fire into state once disposed before it elapses`() = runTest {
        val (scope, viewModel) = harness(autoDismissAfter = 4000.milliseconds)

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Success)))
        scope.runCurrent()

        viewModel.dispose()
        scope.advanceTimeBy(5000)
        scope.runCurrent()

        // Disposal freezes state as it was — the toast is neither dismissed by the stale timer
        // nor mutated by any later call; it simply stops receiving updates.
        viewModel.state.value.toasts.map { it.id } shouldBe listOf("a")
    }

    @Test
    fun `after disposal, dismiss and setProcess intents are also safe no-ops`() = runTest {
        val (scope, viewModel) = harness()

        viewModel.handle(FeedbackIntent.Post(message("a", severity = FeedbackSeverity.Error)))
        scope.runCurrent()
        viewModel.dispose()

        viewModel.handle(FeedbackIntent.Dismiss("a"))
        viewModel.handle(FeedbackIntent.SetProcess(ProcessIndicator.InProgress("x")))
        scope.runCurrent()

        viewModel.state.value.toasts.map { it.id } shouldBe listOf("a")
        viewModel.state.value.status.process shouldBe ProcessIndicator.Idle
    }
}
