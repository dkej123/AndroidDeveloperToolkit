package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent

/**
 * The device bar (task 011, `design/README.md` §1's Device bar), with task 043's supplied visual
 * treatment applied: 30px `header`-background row, the per-state status dot ([StatusDotIcon]),
 * name/serial/connection-chip selector, right-aligned online count + refresh icon button, and the
 * unauthorized-state "Retry" link and amber banner row. Mounted into
 * [dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel.deviceContextSlot].
 *
 * `design/README.md`'s loading state calls for a "10px teal spinner"; this renders a static teal
 * dot instead (no animation timer) — a documented native simplification, consistent with task 013's
 * established "no real-delay/animation tests" boundary and this task's own "no new behavior" scope.
 *
 * Only the two raw interactions the bar exposes are forwarded to the caller: [onToggle] (click the
 * selector, opens/closes the picker) and [onRefresh] (the refresh button and, in the unauthorized
 * state, the "Retry" link — both are the same underlying re-query intent; the supplied design gives
 * them different copy for the same action rather than a second intent).
 */
class DeviceContextBarPanel(
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) : JBPanel<DeviceContextBarPanel>(BorderLayout()) {

    private val selectorLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11.5f))
        foreground = AdbToolboxTheme.Colors.text
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle()
        })
    }

    private val serialLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    private val chipLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.groupLabel.deriveFont(JBUI.scale(9f))
        foreground = AdbToolboxTheme.Colors.textFaint
        border = BorderFactory.createCompoundBorder(
            SolidChipBorder(AdbToolboxTheme.Colors.borderStrong),
            BorderFactory.createEmptyBorder(1, 4, 1, 4),
        )
        isVisible = false
    }

    private val onlineCountLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    private val retryLabel = JBLabel("Retry").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD)
        foreground = AdbToolboxTheme.Colors.accent
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onRefresh()
        })
    }

    private val refreshButton = JButton(AdbToolboxIcons.Actions.refresh).apply {
        toolTipText = "Refresh device list  ⌘⇧D"
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        addActionListener { onRefresh() }
    }

    private val bannerLabel = JBLabel(UNAUTHORIZED_BANNER_TEXT).apply {
        font = AdbToolboxTheme.Typography.caption
        foreground = AdbToolboxTheme.Colors.amber
        background = AdbToolboxTheme.Colors.amberBg
        isOpaque = true
        border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        isVisible = false
    }

    private val leftRow = JBPanel<Nothing>(FlowLayout(FlowLayout.LEADING, AdbToolboxTheme.Spacing.s3, 0)).apply {
        isOpaque = false
        add(selectorLabel)
        add(serialLabel)
        add(chipLabel)
        add(retryLabel)
    }

    private val rightRow = JBPanel<Nothing>(FlowLayout(FlowLayout.TRAILING, AdbToolboxTheme.Spacing.s3, 0)).apply {
        isOpaque = false
        add(onlineCountLabel)
        add(refreshButton)
    }

    init {
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border)
        add(leftRow, BorderLayout.WEST)
        add(rightRow, BorderLayout.EAST)
        add(bannerLabel, BorderLayout.SOUTH)
    }

    /** Test/verification seam: the selector's current plain-text rendering. */
    val selectorText: String get() = selectorLabel.text

    /** Test/verification seam: the right-side "N online" label's current text (empty when not shown). */
    val onlineCountText: String get() = onlineCountLabel.text

    /** Test-only visibility hook so a test can simulate a real mouse click without a live display. */
    internal val selectorComponentForTest: JComponent get() = selectorLabel

    /** Test-only visibility hook for the unauthorized-state "Retry" link. */
    internal val retryComponentForTest: JComponent get() = retryLabel

    /** Test-only visibility hook: the refresh icon button (nested inside [rightRow], not a direct child). */
    internal val refreshButtonForTest: JButton get() = refreshButton

    /** Test/verification seam: the connection-kind chip's current text/visibility ("USB"/"Wi-Fi"). */
    val connectionChipVisible: Boolean get() = chipLabel.isVisible
    val connectionChipText: String get() = chipLabel.text

    fun update(bar: DeviceBarPresentation) {
        chipLabel.isVisible = false
        onlineCountLabel.text = ""
        retryLabel.isVisible = false
        bannerLabel.isVisible = false
        serialLabel.text = ""

        when (bar) {
            DeviceBarPresentation.Loading -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.brand, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.textDim
                selectorLabel.text = "Querying adb devices…"
            }
            DeviceBarPresentation.NoDevice -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.textFaint, filled = false)
                selectorLabel.foreground = AdbToolboxTheme.Colors.textDim
                selectorLabel.text = "No device connected"
            }
            is DeviceBarPresentation.Online -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.green, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.text
                selectorLabel.text = "${bar.device.model ?: bar.device.serial} (${bar.device.serial})"
                serialLabel.text = bar.device.serial.toString()
                chipLabel.text = connectionChipText(bar.device.connectionKind)
                chipLabel.isVisible = true
                onlineCountLabel.text = "${bar.onlineCount} online"
            }
            is DeviceBarPresentation.Unauthorized -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.amber, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.amber
                selectorLabel.text = "${bar.device.model ?: bar.device.serial} — unauthorized"
                retryLabel.isVisible = true
                bannerLabel.isVisible = true
            }
            is DeviceBarPresentation.Offline -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.textFaint, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.textDim
                selectorLabel.text = "${bar.device.model ?: bar.device.serial} — offline"
            }
            is DeviceBarPresentation.Error -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.red, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.red
                selectorLabel.text = bar.message
            }
        }
    }

    private fun connectionChipText(kind: DeviceConnectionKind) = when (kind) {
        DeviceConnectionKind.Usb -> "USB"
        DeviceConnectionKind.Wifi -> "Wi-Fi"
    }

    private companion object {
        const val UNAUTHORIZED_BANNER_TEXT =
            "Accept the “Allow USB debugging” prompt on the device, then retry."
    }
}
