package dev.acme.adbtoolbox.intellij.feedback

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.domain.feedback.ProcessIndicator
import dev.acme.adbtoolbox.domain.feedback.StatusState
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton

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
        font = AdbToolboxTheme.Typography.mono.deriveFont(Font.BOLD, JBUI.scale(9f))
        icon = StatusDotIcon(AdbToolboxTheme.Colors.brand, filled = true, diameter = 5)
        iconTextGap = JBUI.scale(5)
        border = JBUI.Borders.empty(1, 6)
    }

    // `runningChipStyle`: padding 1px 6px, radius 4, tinted fill + 1px border.
    private val processChip = RoundedSurface(null, null, radius = { JBUI.scale(4) }).apply {
        layout = BorderLayout()
        isVisible = false
        add(processLabel, BorderLayout.CENTER)
    }

    private val messageLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUI.scale(9.5f))
        foreground = AdbToolboxTheme.Colors.textDim
    }

    // A real JButton, not a JBLabel with a MouseListener: task 050's keyboard-only pass found the
    // status bar's only interactive control unreachable by Tab and inert on Enter/Space — the same
    // fix applied to the device bar's selector/retry controls
    // ([dev.acme.adbtoolbox.intellij.devicebar.DeviceContextBarPanel]).
    private val overrideChipLabel = JButton("").apply {
        // `overrideChipStyle`: 9px/700 UI font, amber text and 1px amber border, radius 3, padding 1px 5px.
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(9f))
        foreground = AdbToolboxTheme.Colors.amber
        border = BorderFactory.createCompoundBorder(
            SolidChipBorder(AdbToolboxTheme.Colors.amber, radius = { JBUI.scale(3) }),
            JBUI.Borders.empty(1, 5),
        )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Revert font scale, density and proxy on this device"
        isContentAreaFilled = false
        isBorderPainted = true
        isFocusPainted = false
        isOpaque = false
        margin = java.awt.Insets(0, 0, 0, 0)
        isVisible = false
        addActionListener { onResetOverrides() }
    }

    init {
        preferredSize = java.awt.Dimension(0, AdbToolboxTheme.Sizes.statusBar)
        minimumSize = java.awt.Dimension(0, AdbToolboxTheme.Sizes.statusBar)
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4),
        )
        // `statusBarStyle`: one vertically centered flex row with an 8px gap. The message is the
        // only flexible child, so a long last-command message ellipsises instead of pushing the
        // required "reset all" action out of the bar at narrow widths.
        val row = flexRow(AdbToolboxTheme.Spacing.s4, processChip, messageLabel, overrideChipLabel, fill = messageLabel)
        add(row, BorderLayout.CENTER)
    }

    /** Test/verification seam: the override chip's current text/visibility. */
    val overrideChipVisible: Boolean get() = overrideChipLabel.isVisible
    val overrideChipText: String get() = overrideChipLabel.text

    /** Test-only visibility hook so a test can simulate a real click/keyboard activation without a live display. */
    internal val overrideChipComponentForTest: JButton get() = overrideChipLabel

    fun update(status: StatusState) {
        when (val process = status.process) {
            ProcessIndicator.Idle -> {
                processLabel.text = ""
                processChip.isVisible = false
            }
            is ProcessIndicator.InProgress -> {
                processLabel.text = process.label
                processChip.isVisible = true
                val recording = process.label.startsWith("REC", ignoreCase = true)
                val color = if (recording) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.brand
                processLabel.foreground = color
                processLabel.icon = StatusDotIcon(color, filled = true, diameter = 5)
                processChip.fill = if (recording) AdbToolboxTheme.Colors.redBg else AdbToolboxTheme.Colors.brandBg
                processChip.outline = if (recording) AdbToolboxTheme.Colors.redBorder else AdbToolboxTheme.Colors.brandBorder
            }
        }
        messageLabel.text = status.message.orEmpty()
    }

    /** Populates the "N overrides · reset all" chip (`design/README.md` §8); hidden when [count] is 0. */
    fun updateOverrideCount(count: Int) {
        overrideChipLabel.isVisible = count > 0
        overrideChipLabel.text = when {
            count <= 0 -> ""
            count == 1 -> "1 override · reset all"
            else -> "$count overrides · reset all"
        }
    }
}
