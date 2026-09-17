package dev.acme.adbtoolbox.intellij.ui.mirroring

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.mirroring.MirroringOptionsApplyResult
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionFieldError
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsDraft
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme

/**
 * Task 040's apply/cancel behavior, kept as a [BasePlatformTestCase] (Swing components need the
 * platform test sandbox), matching [dev.acme.adbtoolbox.intellij.settings.AdbToolboxSettingsConfigurableTest]'s
 * shape and coverage boundary — see [MirroringOptionsFormController]'s class doc for why this
 * controller, not [MirroringOptionsDialog] itself, is exercised directly.
 */
class MirroringOptionsFormControllerTest : BasePlatformTestCase() {

    private class FakeBackend(
        var persisted: MirroringOptions = MirroringOptions.DEFAULT,
        private val resultFactory: ((MirroringOptionsDraft) -> MirroringOptionsApplyResult)? = null,
    ) : MirroringOptionsEditorBackend {
        val candidates = mutableListOf<MirroringOptionsDraft>()
        var readCount = 0

        override fun read(): MirroringOptions {
            readCount++
            return persisted
        }

        override fun apply(candidate: MirroringOptionsDraft): MirroringOptionsApplyResult {
            candidates += candidate
            val result = resultFactory?.invoke(candidate) ?: MirroringOptionsApplyResult.Applied(
                MirroringOptions(candidate.stayAwake, candidate.showTouches, candidate.maxSize, candidate.videoBitRateMbps),
            )
            if (result is MirroringOptionsApplyResult.Applied) persisted = result.options
            return result
        }
    }

    fun `test the numeric option fields use the design system's mono font`() {
        val controller = MirroringOptionsFormController(FakeBackend())

        assertEquals(AdbToolboxTheme.Typography.mono, controller.maxSize.font)
        assertEquals(AdbToolboxTheme.Typography.mono, controller.videoBitRateMbps.font)
    }

    fun `test the form presents every persisted field on creation`() {
        val backend = FakeBackend(persisted = MirroringOptions(stayAwake = true, showTouches = true, maxSize = 1920, videoBitRateMbps = 8))

        val controller = MirroringOptionsFormController(backend)

        assertTrue(controller.stayAwake.isSelected)
        assertTrue(controller.showTouches.isSelected)
        assertEquals("1920", controller.maxSize.text)
        assertEquals("8", controller.videoBitRateMbps.text)
    }

    fun `test a blank max size and bit rate present as empty fields`() {
        val backend = FakeBackend(persisted = MirroringOptions.DEFAULT)

        val controller = MirroringOptionsFormController(backend)

        assertEquals("", controller.maxSize.text)
        assertEquals("", controller.videoBitRateMbps.text)
    }

    fun `test apply persists the edited valid values and reports the dialog may close`() {
        val backend = FakeBackend()
        val controller = MirroringOptionsFormController(backend)
        controller.stayAwake.isSelected = true
        controller.maxSize.text = "1280"
        controller.videoBitRateMbps.text = "4"

        val closable = controller.apply()

        assertTrue(closable)
        assertEquals(1, backend.candidates.size)
        assertEquals(
            MirroringOptionsDraft(stayAwake = true, maxSize = 1280, videoBitRateMbps = 4),
            backend.candidates.single(),
        )
        assertEquals(MirroringOptions(stayAwake = true, maxSize = 1280, videoBitRateMbps = 4), backend.persisted)
        assertNull(controller.errorMessage)
    }

    fun `test a non-numeric field is rejected locally without ever reaching the backend`() {
        val backend = FakeBackend()
        val controller = MirroringOptionsFormController(backend)
        controller.maxSize.text = "not-a-number"

        val closable = controller.apply()

        assertFalse(closable)
        assertTrue(backend.candidates.isEmpty())
        assertNotNull(controller.errorMessage)
    }

    fun `test an out-of-range value is rejected by the backend and reported without persisting`() {
        val backend = FakeBackend(
            resultFactory = { MirroringOptionsApplyResult.Invalid(setOf(MirroringOptionFieldError.MaxSizeOutOfRange)) },
        )
        val original = backend.persisted
        val controller = MirroringOptionsFormController(backend)
        controller.maxSize.text = "999999"

        val closable = controller.apply()

        assertFalse(closable)
        assertEquals(original, backend.persisted)
        assertNotNull(controller.errorMessage)
        assertTrue(controller.errorMessage!!.contains("Max size"))
    }

    fun `test cancel (never calling apply) never persists an edited draft`() {
        val backend = FakeBackend(persisted = MirroringOptions(maxSize = 1920))
        val controller = MirroringOptionsFormController(backend)
        controller.maxSize.text = "1280"
        controller.stayAwake.isSelected = true

        // Simulates a Cancel/Escape/close-box dismissal: apply() is simply never invoked.

        assertEquals(MirroringOptions(maxSize = 1920), backend.persisted)
        assertTrue(backend.candidates.isEmpty())
    }

    fun `test reset restores the persisted values and clears a previous error`() {
        val backend = FakeBackend(persisted = MirroringOptions(maxSize = 1920))
        val controller = MirroringOptionsFormController(backend)
        controller.maxSize.text = "not-a-number"
        controller.apply()
        assertNotNull(controller.errorMessage)

        controller.reset()

        assertEquals("1920", controller.maxSize.text)
        assertNull(controller.errorMessage)
    }

    fun `test applying options while a session could be active never touches session state - the controller has no session dependency`() {
        // The controller only ever talks to its MirroringOptionsEditorBackend; it has no reference
        // to MirroringSessionManager/MirroringViewModel at all, so it is structurally impossible for
        // an options edit here to reach into an already-running session. The "never mutates an
        // active session silently" guarantee is proven at the MirroringViewModel layer (see
        // MirroringViewModelTest), which is the only place that reads options at start() time.
        val backend = FakeBackend(persisted = MirroringOptions(maxSize = 1920))
        val controller = MirroringOptionsFormController(backend)

        controller.maxSize.text = "1280"
        controller.apply()

        assertEquals(MirroringOptions(maxSize = 1280), backend.persisted)
    }
}
