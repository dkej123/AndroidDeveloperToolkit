package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.jetbrains.JBR
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import java.awt.Color
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JRootPane

/**
 * Older IDEs wrap DialogWrapper content in their custom title bar even for undecorated dialogs.
 * Attach the original DialogWrapper content directly, retaining its shortcuts and lifecycle,
 * and leave the rounded Device Cockpit surface as the only visible window border.
 */
internal object DeepLinkWindowSurface {
    private val titleBarProperties = setOf("apple.awt.fullWindowContent", "apple.awt.transparentTitleBar")

    fun install(root: JRootPane, dialogContent: JComponent) {
        if (root.contentPane !== dialogContent) root.contentPane = dialogContent
        root.border = BorderFactory.createEmptyBorder()
    }

    /** Opaque: the native window is rounded (below), so nothing outside the surface needs to be see-through. */
    fun windowBackground(): Color = DeviceCockpitTheme.Colors.panel

    /**
     * DialogWrapper's show() decorates the root pane for a macOS title bar even when the dialog is
     * undecorated: fullWindowContent / transparentTitleBar (a native frame with square corners behind
     * the rounded surface) and a title-bar-high top inset (an empty strip above it). Undo both whenever
     * the platform applies them, before or after the window shows.
     */
    fun keepBorderless(root: JRootPane) {
        fun undo() {
            titleBarProperties.forEach { key -> if (root.getClientProperty(key) == true) root.putClientProperty(key, false) }
            val insets = root.border?.getBorderInsets(root)
            if (insets != null && (insets.top or insets.left or insets.bottom or insets.right) != 0) root.border = BorderFactory.createEmptyBorder()
        }
        root.addPropertyChangeListener { event -> if (event.propertyName in titleBarProperties || event.propertyName == "border") undo() }
        undo()
    }

    /** The rounded surface's radius: 8 px (`DeviceDeepLinkContent` paints a 16 px arc). */
    fun cornerRadius(): Float = JBUIScale.scale(8f)
    fun cornerArc(): Int = JBUI.scale(16)

    /**
     * Square corners on macOS (a square native shadow, black pixels without it) are clipped by rounding the
     * native window itself; the shadow follows the rounded shape.
     */
    private fun roundNativeCorners(dialog: JDialog) {
        runCatching { if (JBR.isRoundedCornersManagerSupported()) JBR.getRoundedCornersManager().setRoundedCorners(dialog, cornerRadius()) }
    }

    /**
     * The window stays opaque in the surface colour. Per-pixel translucency is no longer needed for the
     * rounded corners, and on macOS it made every resize and many repaints recomposite the whole dialog,
     * which flickered intermittently.
     */
    fun attach(dialog: JDialog) {
        keepBorderless(dialog.rootPane)
        roundNativeCorners(dialog)
        dialog.background = windowBackground()
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) { roundNativeCorners(dialog) }
        })
    }
}
