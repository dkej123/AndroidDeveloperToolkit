package io.github.dkej123.devicecockpit.intellij.ui.mirroring

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsIntent
import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsViewModel
import io.github.dkej123.devicecockpit.application.mirroring.MirroringOptionsViewState
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.RoundedSurface
import io.github.dkej123.devicecockpit.intellij.ui.common.VerticalStackLayout
import io.github.dkej123.devicecockpit.intellij.ui.common.designCombo
import io.github.dkej123.devicecockpit.intellij.ui.common.flexRow
import io.github.dkej123.devicecockpit.intellij.ui.common.flexSpacer
import io.github.dkej123.devicecockpit.intellij.ui.common.labeledColumn
import io.github.dkej123.devicecockpit.intellij.ui.common.responsiveColumns
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The Screen section's inline "Mirroring options" panel (design §3, opened from the Mirror split
 * button's caret), replacing the former options dialog. Every change applies at once through
 * [MirroringOptionsViewModel] — there is no OK button — and, like before, only affects the next
 * scrcpy start ("applies on next start" while a session runs).
 *
 * `mirOptsPanelStyle`: 10px side margin, `header` fill, 1px `border`, radius 5, padding 8 0 10,
 * rows 8px apart: title + note, the two combos, then three checkboxes 5px apart.
 */
class MirroringOptionsPanel(
    private val viewModel: MirroringOptionsViewModel,
    scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : JPanel(BorderLayout()) {

    val maxSizeCombo = designCombo<Int?> { value -> value?.toString() ?: "Original" }.apply {
        toolTipText = "scrcpy --max-size"
        getAccessibleContext().accessibleName = "Max resolution"
    }
    val bitRateCombo = designCombo<Int?> { value -> if (value == null) "8 Mbps (default)" else "$value Mbps" }.apply {
        toolTipText = "scrcpy --video-bit-rate"
        getAccessibleContext().accessibleName = "Bitrate"
    }
    val stayAwakeBox = checkBox("Stay awake while mirroring", "scrcpy --stay-awake (USB only)")
    val showTouchesBox = checkBox("Show touches", "scrcpy --show-touches")
    val turnScreenOffBox = checkBox("Turn device screen off", "scrcpy --turn-screen-off — mirror stays on")

    val noteLabel = JBLabel(NOTE_IDLE).apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUIScale.scale(10f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    /** Set while [render] writes into the controls, so it never echoes back as an edit. */
    private var rendering = false

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        val title = JBLabel("Mirroring options").apply {
            font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(11.5f))
            foreground = AdbToolboxTheme.Colors.text
        }
        val spacer = flexSpacer()
        val head = flexRow(AdbToolboxTheme.Spacing.s3, title, spacer, noteLabel, fill = spacer).apply {
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        }
        val checks = JPanel(VerticalStackLayout { JBUIScale.scale(5) }).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
            add(stayAwakeBox)
            add(showTouchesBox)
            add(turnScreenOffBox)
        }
        val card = RoundedSurface(AdbToolboxTheme.Colors.header, AdbToolboxTheme.Colors.border).apply {
            layout = VerticalStackLayout { AdbToolboxTheme.Spacing.s4 }
            border = JBUI.Borders.empty(8, 0, 10, 0)
            add(head)
            add(responsiveColumns(labeledColumn("Max resolution", maxSizeCombo), labeledColumn("Bitrate", bitRateCombo)))
            add(checks)
        }
        add(card, BorderLayout.CENTER)

        maxSizeCombo.addActionListener { if (!rendering) change(MirroringOptionsIntent.UpdateMaxSize(maxSizeCombo.selectedItem as Int?)) }
        bitRateCombo.addActionListener { if (!rendering) change(MirroringOptionsIntent.UpdateVideoBitRateMbps(bitRateCombo.selectedItem as Int?)) }
        stayAwakeBox.addActionListener { if (!rendering) change(MirroringOptionsIntent.UpdateStayAwake(stayAwakeBox.isSelected)) }
        showTouchesBox.addActionListener { if (!rendering) change(MirroringOptionsIntent.UpdateShowTouches(showTouchesBox.isSelected)) }
        turnScreenOffBox.addActionListener { if (!rendering) change(MirroringOptionsIntent.UpdateTurnScreenOff(turnScreenOffBox.isSelected)) }

        viewModel.state
            .onEach { state -> withContext(dispatchers.main) { render(state) } }
            .launchIn(scope)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    /** "applies on next start" while a session runs; otherwise where the choice is kept. */
    fun setMirroringRunning(running: Boolean) {
        noteLabel.text = if (running) NOTE_RUNNING else NOTE_IDLE
    }

    internal fun render(state: MirroringOptionsViewState) {
        rendering = true
        try {
            val draft = state.draft
            maxSizeCombo.model = DefaultComboBoxModel(maxSizeItems(draft.maxSize).toTypedArray())
            maxSizeCombo.selectedItem = draft.maxSize
            // scrcpy's own default is 8 Mbps, so an explicit 8 and "not set" are the same choice.
            val bitRate = draft.videoBitRateMbps?.takeIf { it != DEFAULT_BIT_RATE }
            bitRateCombo.model = DefaultComboBoxModel(bitRateItems(bitRate).toTypedArray())
            bitRateCombo.selectedItem = bitRate
            stayAwakeBox.isSelected = draft.stayAwake
            showTouchesBox.isSelected = draft.showTouches
            turnScreenOffBox.isSelected = draft.turnScreenOff
            val busy = state.isLoading || state.isApplying
            listOf(maxSizeCombo, bitRateCombo, stayAwakeBox, showTouchesBox, turnScreenOffBox).forEach { it.isEnabled = !busy }
        } finally {
            rendering = false
        }
    }

    private fun change(edit: MirroringOptionsIntent) {
        viewModel.handle(edit)
        viewModel.handle(MirroringOptionsIntent.Apply)
    }

    private fun checkBox(text: String, tooltip: String) = JBCheckBox(text).apply {
        isOpaque = false
        toolTipText = tooltip
        font = AdbToolboxTheme.Typography.body.deriveFont(JBUIScale.scale(11.5f))
    }

    private companion object {
        const val NOTE_IDLE = "saved for this project"
        const val NOTE_RUNNING = "applies on next start"
        const val DEFAULT_BIT_RATE = 8

        val MAX_SIZE_PRESETS = listOf(1920, 1280, 1024)
        val BIT_RATE_PRESETS = listOf(4, 16, 32)

        /** "Original" (no limit, `null`) first, then sizes largest first — a persisted custom one in place. */
        fun maxSizeItems(current: Int?): List<Int?> =
            listOf<Int?>(null) + (MAX_SIZE_PRESETS + listOfNotNull(current)).distinct().sortedDescending()

        /** Rates ascending, with scrcpy's default (`null`, 8 Mbps) where 8 belongs. */
        fun bitRateItems(current: Int?): List<Int?> {
            val rates = (BIT_RATE_PRESETS + listOfNotNull(current)).distinct().sorted()
            return rates.filter { it < DEFAULT_BIT_RATE } + listOf<Int?>(null) + rates.filter { it > DEFAULT_BIT_RATE }
        }
    }
}
