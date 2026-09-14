package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import dev.acme.adbtoolbox.domain.feedback.StatusState
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory

/**
 * The status bar (task 013, `design/README.md` §8), with task 043's supplied visual treatment
 * applied: 22px `header`-background row with a 1px top border, the running-process chip
 * (teal `brandBg` for mirroring, red `redBg` for recording — a rendering-only convention over
 * [ProcessIndicator.InProgress.label]'s two supplied shapes, "scrcpy" and "REC …"), the dim
 * last-command message, and the amber "N overrides · reset all" chip populated by [updateOverrideCount]
 * from task 014's aggregated per-serial [dev.acme.adbtoolbox.domain.devicecontext.DeviceContextSnapshot.overrides].
 * Mounted into [dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel.feedbackSlot].
 *
 * The chip's click reverts font scale, density and proxy on the current device
 * (`design/README.md` §8) — that revert is task 041's business logic, not this task's dependency
 * list, so [onResetOverrides] defaults to a no-op and is wired to the real behavior by whichever
 * later composition owns it, the same optional-callback seam `openSettings`/`openMirroringOptions`
 * already establish on [dev.acme.adbtoolbox.intellij.toolwindow.AdbToolboxToolWindowPanel].
 */
class FeedbackStatusPanel(
    private val onResetOverrides: () -> Unit = {},
) : JBPanel<FeedbackStatusPanel>(BorderLayout()) {

    private val processLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.monoMeta.deriveFont(Font.BOLD)
        isOpaque = true
        isVisible = false
        border = BorderFactory.createEmptyBorder(1, 4, 1, 4)
    }

    private val messageLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.caption
        foreground = AdbToolboxTheme.Colors.textDim
    }

    private val overrideChipLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.monoMeta.deriveFont(Font.BOLD)
        foreground = AdbToolboxTheme.Colors.amber
        border = BorderFactory.createCompoundBorder(
            SolidChipBorder(AdbToolboxTheme.Colors.amber),
            BorderFactory.createEmptyBorder(1, 4, 1, 4),
        )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Revert font scale, density and proxy on this device"
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onResetOverrides()
        })
    }

    private val leftRow = JBPanel<Nothing>(FlowLayout(FlowLayout.LEADING, AdbToolboxTheme.Spacing.s4, 0)).apply {
        isOpaque = false
        add(processLabel)
        add(messageLabel)
    }

    private val rightRow = JBPanel<Nothing>(FlowLayout(FlowLayout.TRAILING, AdbToolboxTheme.Spacing.s4, 0)).apply {
        isOpaque = false
        add(overrideChipLabel)
    }

    init {
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, AdbToolboxTheme.Colors.border)
        add(leftRow, BorderLayout.WEST)
        add(rightRow, BorderLayout.EAST)
    }

    /** Test/verification seam: the override chip's current text/visibility. */
    val overrideChipVisible: Boolean get() = overrideChipLabel.isVisible
    val overrideChipText: String get() = overrideChipLabel.text

    /** Test-only visibility hook so a test can simulate a real click without a live display. */
    internal val overrideChipComponentForTest get() = overrideChipLabel

    fun update(status: StatusState) {
        when (val process = status.process) {
            ProcessIndicator.Idle -> {
                processLabel.text = ""
                processLabel.isVisible = false
            }
            is ProcessIndicator.InProgress -> {
                processLabel.text = process.label
                processLabel.isVisible = true
                val recording = process.label.startsWith("REC", ignoreCase = true)
                processLabel.foreground = if (recording) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.brand
                processLabel.background = if (recording) AdbToolboxTheme.Colors.redBg else AdbToolboxTheme.Colors.brandBg
            }
        }
        messageLabel.text = status.message.orEmpty()
    }

    /** Populates the "N overrides · reset all" chip (`design/README.md` §8); hidden when [count] is 0. */
    fun updateOverrideCount(count: Int) {
        overrideChipLabel.isVisible = count > 0
        overrideChipLabel.text = if (count > 0) "$count overrides · reset all" else ""
    }
}
