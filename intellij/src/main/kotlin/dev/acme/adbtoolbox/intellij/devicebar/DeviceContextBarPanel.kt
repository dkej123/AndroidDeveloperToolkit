package dev.acme.adbtoolbox.intellij.devicebar

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarPresentation
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent

/**
 * The neutral device bar row (task 011, `design/README.md` §1's Device bar) mounted into
 * [dev.acme.adbtoolbox.intellij.host.AdbToolboxHostPanel.deviceContextSlot]. Purely structural —
 * no color, icon, spacing, or final copy/layout is decided here; that is task 042's concern. This
 * class only renders whichever [DeviceBarPresentation] it is given and forwards the two raw
 * interactions the bar exposes: [onToggle] (click the selector, opens/closes the picker) and
 * [onRefresh] (the refresh control).
 */
class DeviceContextBarPanel(
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) : JBPanel<DeviceContextBarPanel>(BorderLayout()) {

    private val selectorLabel = JBLabel("").apply {
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle()
        })
    }
    private val refreshButton = JButton("Refresh").apply {
        addActionListener { onRefresh() }
    }

    init {
        add(selectorLabel, BorderLayout.CENTER)
        add(refreshButton, BorderLayout.EAST)
    }

    /** Test/verification seam: the selector's current plain-text rendering. */
    val selectorText: String get() = selectorLabel.text

    /** Test-only visibility hook so a test can simulate a real mouse click without a live display. */
    internal val selectorComponentForTest: JComponent get() = selectorLabel

    fun update(bar: DeviceBarPresentation) {
        selectorLabel.text = when (bar) {
            DeviceBarPresentation.Loading -> "Querying adb devices…"
            DeviceBarPresentation.NoDevice -> "No device connected"
            is DeviceBarPresentation.Online ->
                "${bar.device.model ?: bar.device.serial} (${bar.device.serial}) — ${bar.onlineCount} online"
            is DeviceBarPresentation.Unauthorized -> "${bar.device.model ?: bar.device.serial} — unauthorized"
            is DeviceBarPresentation.Offline -> "${bar.device.model ?: bar.device.serial} — offline"
            is DeviceBarPresentation.Error -> bar.message
        }
    }
}
