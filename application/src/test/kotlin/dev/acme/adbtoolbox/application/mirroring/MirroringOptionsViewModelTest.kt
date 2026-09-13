@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.acme.adbtoolbox.application.mirroring

import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.mirroring.FakeMirroringOptionsRepository
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionFieldError
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MirroringOptionsViewModelTest {
    private class TestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    private class Harness(initial: MirroringOptions = MirroringOptions.DEFAULT) {
        val scope = TestScope()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val repository = FakeMirroringOptionsRepository(initial)
        val viewModel = MirroringOptionsViewModel(
            scope = scope,
            dispatchers = TestDispatchers(dispatcher),
            options = MirroringOptionsUseCase(repository),
        )
    }

    @Test
    fun `initial load publishes persisted options as the clean editable draft`() = runTest {
        val persisted = MirroringOptions(stayAwake = true, maxSize = 1920)
        val h = Harness(persisted)

        h.scope.runCurrent()

        val expectedDraft = MirroringOptionsDraft(stayAwake = true, maxSize = 1920)
        h.viewModel.state.value shouldBe MirroringOptionsViewState(
            persisted = expectedDraft,
            draft = expectedDraft,
            isLoading = false,
        )
        h.viewModel.currentOptions.value shouldBe persisted
    }

    @Test
    fun `edit intents update the draft synchronously and mark it modified`() = runTest {
        val h = Harness()
        h.scope.runCurrent()

        h.viewModel.handle(MirroringOptionsIntent.UpdateStayAwake(true))
        h.viewModel.handle(MirroringOptionsIntent.UpdateShowTouches(true))
        h.viewModel.handle(MirroringOptionsIntent.UpdateMaxSize(1280))
        h.viewModel.handle(MirroringOptionsIntent.UpdateVideoBitRateMbps(4))

        h.viewModel.state.value.draft shouldBe MirroringOptionsDraft(
            stayAwake = true,
            showTouches = true,
            maxSize = 1280,
            videoBitRateMbps = 4,
        )
        h.viewModel.state.value.isModified shouldBe true
    }

    @Test
    fun `reset restores the last persisted values and clears validation errors`() = runTest {
        val persisted = MirroringOptions(maxSize = 1280)
        val h = Harness(persisted)
        h.scope.runCurrent()
        h.viewModel.handle(MirroringOptionsIntent.UpdateMaxSize(-1))
        h.viewModel.handle(MirroringOptionsIntent.Apply)
        h.scope.runCurrent()
        h.viewModel.state.value.validationErrors shouldBe setOf(MirroringOptionFieldError.MaxSizeOutOfRange)

        h.viewModel.handle(MirroringOptionsIntent.Reset)

        h.viewModel.state.value.draft shouldBe MirroringOptionsDraft(maxSize = 1280)
        h.viewModel.state.value.validationErrors shouldBe emptySet()
        h.viewModel.state.value.isModified shouldBe false
    }

    @Test
    fun `invalid apply keeps the edited draft and exposes errors without persisting`() = runTest {
        val h = Harness()
        h.scope.runCurrent()
        h.viewModel.handle(MirroringOptionsIntent.UpdateVideoBitRateMbps(-1))

        h.viewModel.handle(MirroringOptionsIntent.Apply)
        h.scope.runCurrent()

        h.viewModel.state.value.draft.videoBitRateMbps shouldBe -1
        h.viewModel.state.value.validationErrors shouldBe setOf(MirroringOptionFieldError.VideoBitRateOutOfRange)
        h.viewModel.state.value.isApplying shouldBe false
        h.repository.writes shouldBe emptyList()
        h.viewModel.currentOptions.value shouldBe MirroringOptions.DEFAULT
    }

    @Test
    fun `valid apply persists values, updates currentOptions, and leaves a clean draft`() = runTest {
        val h = Harness()
        h.scope.runCurrent()
        h.viewModel.handle(MirroringOptionsIntent.UpdateStayAwake(true))
        h.viewModel.handle(MirroringOptionsIntent.UpdateMaxSize(1920))

        h.viewModel.handle(MirroringOptionsIntent.Apply)
        h.scope.runCurrent()

        val applied = MirroringOptions(stayAwake = true, maxSize = 1920)
        h.repository.writes shouldBe listOf(applied)
        h.viewModel.state.value.persisted shouldBe MirroringOptionsDraft(stayAwake = true, maxSize = 1920)
        h.viewModel.state.value.draft shouldBe MirroringOptionsDraft(stayAwake = true, maxSize = 1920)
        h.viewModel.state.value.isModified shouldBe false
        h.viewModel.currentOptions.value shouldBe applied
    }

    @Test
    fun `applying new options never mutates a value already read for an active session`() = runTest {
        val h = Harness(MirroringOptions(maxSize = 1920))
        h.scope.runCurrent()
        val readAtStartTime = h.viewModel.currentOptions.value

        h.viewModel.handle(MirroringOptionsIntent.UpdateMaxSize(1280))
        h.viewModel.handle(MirroringOptionsIntent.Apply)
        h.scope.runCurrent()

        readAtStartTime shouldBe MirroringOptions(maxSize = 1920)
        h.viewModel.currentOptions.value shouldBe MirroringOptions(maxSize = 1280)
    }
}
