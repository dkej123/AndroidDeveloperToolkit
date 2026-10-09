package io.github.dkej123.devicecockpit.intellij.inspector

import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import io.github.dkej123.devicecockpit.intellij.ui.common.FlexRowLayout
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import javax.swing.JPanel

/** The newest open inspector tab; [capturedAtMillis] is null until its first capture lands. */
data class InspectorOpenStatus(val capturedAtMillis: Long?)

/**
 * Capture's "Inspector open" row (design §9, `resetRowStyle`): "Inspector open · captured 12:04:31"
 * with **Show** (brings the tab forward) and **Re-capture** links; hidden while no tab is open.
 */
class InspectorOpenRow(
    onShow: () -> Unit,
    onRecapture: () -> Unit,
    private val timeZone: TimeZone = TimeZone.getDefault(),
) : JPanel(FlexRowLayout(AdbToolboxTheme.Spacing.s4)) {
    private val note = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val showLink = DesignButton("Show", DesignButtonStyle.LINK).apply {
        toolTipText = "Show the Layout Inspector editor tab"
        addActionListener { onShow() }
    }
    private val recaptureLink = DesignButton("Re-capture", DesignButtonStyle.LINK).apply {
        toolTipText = "Re-capture screen and hierarchy"
        addActionListener { onRecapture() }
    }

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
        add(note, FlexRowLayout.FILL)
        add(showLink)
        add(recaptureLink)
        isVisible = false
    }

    fun render(status: InspectorOpenStatus?) {
        isVisible = status != null
        note.text = when (val at = status?.capturedAtMillis) {
            null -> "Inspector open · capturing…"
            else -> "Inspector open · captured " + SimpleDateFormat("HH:mm:ss").apply { timeZone = this@InspectorOpenRow.timeZone }.format(Date(at))
        }
        revalidate()
        repaint()
    }

    internal val noteForTest: String get() = note.text
    internal val showLinkForTest: DesignButton get() = showLink
    internal val recaptureLinkForTest: DesignButton get() = recaptureLink
}
