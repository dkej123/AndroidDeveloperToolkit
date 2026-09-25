package dev.acme.adbtoolbox.intellij.devicebar

import dev.acme.adbtoolbox.intellij.ui.common.ShortcutHints
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import dev.acme.adbtoolbox.domain.device.DeviceConnectionKind
import dev.acme.adbtoolbox.intellij.icons.AdbToolboxIcons
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton

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

    // A real JButton, not a JBLabel with a MouseListener: task 050's keyboard-only pass found the
    // label variant unreachable by Tab and inert on Enter/Space — the device picker's only trigger
    // was a mouse click. JButton gives Tab-reachability, Enter/Space activation, a platform focus
    // ring, and an accessible name derived from its own text for free.
    private val selectorLabel = JButton("").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11.5f))
        foreground = AdbToolboxTheme.Colors.text
        iconTextGap = AdbToolboxTheme.Spacing.s3
        border = JBUI.Borders.empty()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Change device"
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        horizontalAlignment = JButton.LEFT
        margin = java.awt.Insets(0, 0, 0, 0)
        addActionListener { onToggle() }
    }

    private val serialLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    private val chipLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.groupLabel.deriveFont(JBUI.scale(9f))
        foreground = AdbToolboxTheme.Colors.textDim
        border = BorderFactory.createCompoundBorder(
            SolidChipBorder(AdbToolboxTheme.Colors.border, radius = { JBUI.scale(3) }),
            JBUI.Borders.empty(1, 4),
        )
        isVisible = false
    }

    private val onlineCountLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }

    // Same JButton-over-JBLabel fix as [selectorLabel] — this was the unauthorized-state's only
    // recovery action and was previously unreachable by keyboard.
    private val retryLabel = JButton("Retry").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(11f))
        border = JBUI.Borders.empty()
        foreground = AdbToolboxTheme.Colors.accent
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Re-query the device after accepting the USB debugging prompt"
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        margin = java.awt.Insets(0, 0, 0, 0)
        isVisible = false
        addActionListener { onRefresh() }
    }

    private val refreshButton = JButton(AdbToolboxIcons.Actions.refresh).apply {
        preferredSize = java.awt.Dimension(AdbToolboxTheme.Sizes.iconButton, AdbToolboxTheme.Sizes.iconButton)
        margin = java.awt.Insets(0, 0, 0, 0)
        toolTipText = ShortcutHints.withAction("Refresh device list", "AdbToolbox.RefreshDevices")
        getAccessibleContext().accessibleName = "Refresh device list"
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        addActionListener { onRefresh() }
    }

    private val bannerLabel = JBLabel(UNAUTHORIZED_BANNER_TEXT).apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUI.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.amber
        background = AdbToolboxTheme.Colors.amberBg
        isOpaque = true
        border = JBUI.Borders.empty(6, 10)
        isVisible = false
    }

    // `caretIconStyle`: 5px chevron after the connection chip, marking the selector as a picker.
    private val caretLabel = JBLabel(CaretIcon(AdbToolboxTheme.Colors.textDim)).apply { isVisible = false }

    private val row = run {
        val spacer = flexSpacer()
        flexRow(
            AdbToolboxTheme.Spacing.s3,
            selectorLabel, serialLabel, chipLabel, caretLabel, retryLabel, spacer, onlineCountLabel, refreshButton,
            fill = spacer,
            shrink = selectorLabel,
        ).apply {
            // `deviceBarStyle`: 30px row, `padding: 0 4px 0 8px`, gap 6, every child vertically centered.
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s4, 0, AdbToolboxTheme.Spacing.s2)
            preferredSize = java.awt.Dimension(0, AdbToolboxTheme.Sizes.deviceBar - JBUI.scale(1))
        }
    }

    init {
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border)
        add(row, BorderLayout.CENTER)
        add(bannerLabel, BorderLayout.SOUTH)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = applyResponsiveLayout(width)
        })
    }

    /** Fixed 30px bar; the unauthorized banner adds its own height below it (`warnBannerStyle`). */
    override fun getPreferredSize(): java.awt.Dimension {
        val banner = if (bannerLabel.isVisible) bannerLabel.preferredSize.height else 0
        return java.awt.Dimension(super.getPreferredSize().width, AdbToolboxTheme.Sizes.deviceBar + banner)
    }

    override fun getMinimumSize(): java.awt.Dimension = java.awt.Dimension(0, preferredSize.height)

    private var currentOnline: DeviceBarPresentation.Online? = null
    private var isNarrow = false

    /** `design/README.md`'s narrow-width rule: below [AdbToolboxTheme.Breakpoints.narrow] the
     * device bar drops the serial and shortens "N online" to "N" — mirrors
     * [dev.acme.adbtoolbox.intellij.devicefacts.DeviceFactsPanel.applyResponsiveLayout]'s pattern. */
    internal fun applyResponsiveLayout(width: Int) {
        isNarrow = width < AdbToolboxTheme.Breakpoints.narrow
        currentOnline?.let { renderOnline(it) }
    }

    /** Test/verification seam: the selector's current plain-text rendering. */
    val selectorText: String get() = selectorLabel.text

    /** Returns keyboard focus to the selector button after the device picker popup it opened closes
     * (Escape, a confirmed selection, or a mouse-away dismiss) — otherwise a keyboard-only user's
     * focus would be left on a component the popup no longer occludes but that Swing itself never
     * re-focused on their behalf. */
    fun focusSelector() {
        focusSelectorCallCountForTest++
        selectorLabel.requestFocusInWindow()
    }

    /** Test-only visibility hook: counts [focusSelector] invocations — see
     * [DevicePickerListPanel.focusListCallCountForTest] for why a call count, not a live focus
     * assertion, is this headless sandbox's proof. */
    internal var focusSelectorCallCountForTest: Int = 0
        private set

    /** Test/verification seam: the right-side "N online" label's current text (empty when not shown). */
    val onlineCountText: String get() = onlineCountLabel.text

    /** Test/verification seam: the serial label's current text (empty in the narrow width class). */
    val serialText: String get() = serialLabel.text

    /** Test-only visibility hook so a test can simulate a real click/keyboard activation without a live display. */
    internal val selectorComponentForTest: JButton get() = selectorLabel

    /** Test-only visibility hook for the unauthorized-state "Retry" button. */
    internal val retryComponentForTest: JButton get() = retryLabel

    /** Test-only visibility hook: the refresh icon button (nested inside the bar row, not a direct child). */
    internal val refreshButtonForTest: JButton get() = refreshButton

    /** Test/verification seam: the connection-kind chip's current text/visibility ("USB"/"Wi-Fi"). */
    val connectionChipVisible: Boolean get() = chipLabel.isVisible
    val connectionChipText: String get() = chipLabel.text

    fun update(bar: DeviceBarPresentation) {
        chipLabel.isVisible = false
        caretLabel.isVisible = false
        onlineCountLabel.text = ""
        retryLabel.isVisible = false
        bannerLabel.isVisible = false
        serialLabel.text = ""
        currentOnline = bar as? DeviceBarPresentation.Online
        // Device name and the unauthorized label are 11.5/600; the loading and no-device messages
        // are regular `textDim` copy (`deviceLoadingTextStyle`, `noDeviceTextStyle`).
        selectorLabel.toolTipText = "Change device"
        val emphasized = bar is DeviceBarPresentation.Online || bar is DeviceBarPresentation.Unauthorized
        selectorLabel.font = AdbToolboxTheme.Typography.body.deriveFont(
            if (emphasized) Font.BOLD else Font.PLAIN,
            JBUI.scale(11.5f),
        )

        when (bar) {
            DeviceBarPresentation.Loading -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.brand, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.textDim
                selectorLabel.text = "Querying adb devices…"
            }
            is DeviceBarPresentation.SelectDevice -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.green, filled = false)
                selectorLabel.foreground = AdbToolboxTheme.Colors.text
                selectorLabel.text = "${bar.deviceCount} devices — select one"
                caretLabel.isVisible = true
            }
            DeviceBarPresentation.NoDevice -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.textFaint, filled = false)
                selectorLabel.foreground = AdbToolboxTheme.Colors.textDim
                selectorLabel.text = "No device connected"
            }
            is DeviceBarPresentation.Online -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.green, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.text
                selectorLabel.text = bar.device.displayName
                chipLabel.text = connectionChipText(bar.device.connectionKind)
                chipLabel.isVisible = true
                caretLabel.isVisible = true
                renderOnline(bar)
            }
            is DeviceBarPresentation.Unauthorized -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.amber, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.amber
                selectorLabel.text = "${bar.device.displayName} — unauthorized"
                retryLabel.isVisible = true
                bannerLabel.isVisible = true
            }
            is DeviceBarPresentation.Offline -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.textFaint, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.textDim
                selectorLabel.text = "${bar.device.displayName} — offline"
            }
            is DeviceBarPresentation.Error -> {
                selectorLabel.icon = StatusDotIcon(AdbToolboxTheme.Colors.red, filled = true)
                selectorLabel.foreground = AdbToolboxTheme.Colors.red
                selectorLabel.text = bar.message
                // Discovery errors can be long (tried sources, adb stderr); keep the full text reachable.
                selectorLabel.toolTipText = bar.message
            }
        }
    }

    private fun renderOnline(bar: DeviceBarPresentation.Online) {
        serialLabel.text = if (isNarrow || bar.device.model == null) "" else bar.device.serial.toString()
        onlineCountLabel.text = if (isNarrow) "${bar.onlineCount}" else "${bar.onlineCount} online"
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

/** The selector's 5px caret (`caretIconStyle`: a 1.3px chevron pointing down). */
private class CaretIcon(private val color: java.awt.Color) : javax.swing.Icon {
    override fun getIconWidth(): Int = JBUI.scale(8)
    override fun getIconHeight(): Int = JBUI.scale(8)

    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.stroke = java.awt.BasicStroke(JBUI.scale(1.3f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            val half = JBUI.scale(3f)
            val cx = x + iconWidth / 2f
            val cy = y + iconHeight / 2f
            val path = java.awt.geom.Path2D.Float().apply {
                moveTo(cx - half, cy - half / 2)
                lineTo(cx, cy + half / 2)
                lineTo(cx + half, cy - half / 2)
            }
            g2.draw(path)
        } finally {
            g2.dispose()
        }
    }
}
