package io.github.dkej123.devicecockpit.intellij.ui.mirroring

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsUseCase
import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsViewModel
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.mirroring.FakeMirroringOptionsRepository
import io.github.dkej123.devicecockpit.domain.mirroring.MirroringOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MirroringOptionsPanelTest : BasePlatformTestCase() {

    /** Loads, applies and renders run inline, so every assertion sees the settled state. */
    private object Inline : DispatcherProvider {
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val main = Dispatchers.Unconfined
    }

    private class Harness(initial: MirroringOptions = MirroringOptions.DEFAULT) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = FakeMirroringOptionsRepository(initial)
        val viewModel = MirroringOptionsViewModel(scope, Inline, MirroringOptionsUseCase(repository))
        val panel = MirroringOptionsPanel(viewModel, scope, Inline)
    }

    fun `test the panel shows the persisted options`() {
        val h = Harness(MirroringOptions(stayAwake = true, maxSize = 1280, videoBitRateMbps = 16, turnScreenOff = true))

        assertEquals(1280, h.panel.maxSizeCombo.selectedItem)
        assertEquals(16, h.panel.bitRateCombo.selectedItem)
        assertTrue(h.panel.stayAwakeBox.isSelected)
        assertFalse(h.panel.showTouchesBox.isSelected)
        assertTrue(h.panel.turnScreenOffBox.isSelected)
        h.scope.cancel()
    }

    fun `test every change is applied at once, with no OK button`() {
        val h = Harness()

        h.panel.turnScreenOffBox.doClick()
        h.panel.maxSizeCombo.selectedItem = 1024

        assertEquals(MirroringOptions(turnScreenOff = true, maxSize = 1024), h.repository.writes.last())
        h.scope.cancel()
    }

    fun `test presets keep a persisted custom size in order and treat 8 Mbps as the default`() {
        val h = Harness(MirroringOptions(maxSize = 1600, videoBitRateMbps = 8))

        val sizes = (0 until h.panel.maxSizeCombo.itemCount).map { h.panel.maxSizeCombo.getItemAt(it) }
        val rates = (0 until h.panel.bitRateCombo.itemCount).map { h.panel.bitRateCombo.getItemAt(it) }

        assertEquals(listOf(null, 1920, 1600, 1280, 1024), sizes)
        assertEquals(listOf(4, null, 16, 32), rates)
        assertNull(h.panel.bitRateCombo.selectedItem)
        assertEquals("8 Mbps (default)", rendered(h.panel.bitRateCombo, null))
        assertEquals("Original", rendered(h.panel.maxSizeCombo, null))
        h.scope.cancel()
    }

    fun `test the note says when a change takes effect`() {
        val h = Harness()

        h.panel.setMirroringRunning(true)
        assertEquals("applies on next start", h.panel.noteLabel.text)

        h.panel.setMirroringRunning(false)
        assertEquals("saved for this project", h.panel.noteLabel.text)
        h.scope.cancel()
    }

    private fun rendered(combo: javax.swing.JComboBox<Int?>, value: Int?): String =
        (combo.renderer.getListCellRendererComponent(javax.swing.JList(), value, 0, false, false) as javax.swing.JLabel).text
}
