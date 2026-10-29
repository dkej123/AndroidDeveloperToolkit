package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.deeplinks.BannerAction
import io.github.dkej123.devicecockpit.application.deeplinks.LaunchBanner
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.intellij.ui.common.*
import java.awt.*
import javax.swing.*

/** The complete modal surface, shared by the live dialog and rendered design checks. */
internal class DeviceDeepLinkContent(
    val form: DeviceDeepLinkPanel,
    serial: DeviceSerial,
    deviceName: String,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) : JPanel(BorderLayout()) {
    val openButton = DeepLinkVisuals.button("Open on device", DesignButtonStyle.PRIMARY, onOpen).apply { name = "deviceDeepLinkOpen" }
    val clearButton = DeepLinkVisuals.button("Clear form", DesignButtonStyle.LINK, onClear).apply { foreground = DeviceCockpitTheme.Colors.textDim; name = "deviceDeepLinkClear"
        font = DeepLinkVisuals.ui(11f)
        preferredSize = Dimension(getFontMetrics(font).stringWidth(text) + JBUI.scale(16), JBUI.scale(24)) }
    var onDismissResult: () -> Unit = {}
    var onResultAction: (BannerAction) -> Unit = {}
    var onCopyOutput: (String) -> Unit = {}
    var onColdStartChanged: (Boolean) -> Unit = {}
    val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(DeepLinkVisuals.label("Open deep link", 12.5f, true, DeviceCockpitTheme.Colors.text), BorderLayout.WEST)
        add(DeepLinkVisuals.label("$deviceName · $serial", 10f).apply {
            font = DeepLinkVisuals.mono(10f)
            horizontalAlignment = SwingConstants.RIGHT
            toolTipText = text
        }, BorderLayout.CENTER)
        add(DeepLinkVisuals.button("×", DesignButtonStyle.LINK, onCancel).apply {
            name = "deviceDeepLinkClose"; toolTipText = "Close (Esc)"
            foreground = DeviceCockpitTheme.Colors.textDim; font = DeepLinkVisuals.ui(14f)
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
        }, BorderLayout.EAST)
    }
    private val banner = DeepLinkResultBanner(form::tileIcon, { onDismissResult() }, { onResultAction(it) }, { onCopyOutput(it) })
    val coldStartBox = DeepLinkCheckBox("Cold start").apply {
        name = "deviceDeepLinkColdStart"
        toolTipText = "Force-stop the app before opening the link (am start -S)"
        isSelected = form.coldStart
        addActionListener { form.coldStart = isSelected; onColdStartChanged(isSelected) }
    }
    private val body = DeepLinkStack().apply {
        row(header); row(form)
        row(JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.emptyTop(12); add(banner) })
    }
    private val bannerRow = banner.parent as JComponent
    private val footer = JPanel(BorderLayout()).apply {
        isOpaque = false; border = JBUI.Borders.emptyTop(14)
        add(coldStartBox, BorderLayout.WEST)
        add(JPanel().apply {
            isOpaque = false; layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(clearButton); add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(DeepLinkVisuals.button("Close", DesignButtonStyle.SECONDARY, onCancel).apply { name = "deviceDeepLinkCloseButton" })
            add(Box.createHorizontalStrut(JBUI.scale(6))); add(openButton)
        }, BorderLayout.EAST)
    }

    /** §5: the last launch result; `null` hides it. */
    fun showResult(result: LaunchBanner?, atMillis: Long, raw: String) {
        banner.show(result, atMillis, raw)
        bannerRow.isVisible = result != null
        revalidate(); repaint()
    }

    /** "Opening…" while `am start -W` runs; the previous banner stays until the new result arrives. */
    fun setBusy(busy: Boolean) {
        openButton.text = if (busy) "Opening…" else "Open on device"
        openButton.isEnabled = !busy
    }

    fun setColdStart(on: Boolean) {
        coldStartBox.isSelected = on
        form.coldStart = on
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(19)
        bannerRow.isVisible = false
        add(DeepLinkVisuals.scroll(body).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }
    /** Exactly as tall as the content; DeviceDeepLinkDialog resizes the window in the same event (no flicker). */
    override fun getPreferredSize() = Dimension(JBUI.scale(600), body.preferredSize.height + footer.preferredSize.height + JBUI.scale(38))
    override fun getMinimumSize() = Dimension(JBUI.scale(480), JBUI.scale(220))
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = DeviceCockpitTheme.Colors.panel
            g.fillRoundRect(0, 0, width, height, DeepLinkWindowSurface.cornerArc(), DeepLinkWindowSurface.cornerArc())
            g.color = DeviceCockpitTheme.Colors.borderStrong
            g.stroke = BasicStroke(com.intellij.ui.scale.JBUIScale.scale(1f))
            g.drawRoundRect(1, 1, width - 2, height - 2, DeepLinkWindowSurface.cornerArc(), DeepLinkWindowSurface.cornerArc())
        } finally { g.dispose() }
    }
}
