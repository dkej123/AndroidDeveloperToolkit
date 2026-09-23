package dev.acme.adbtoolbox.intellij.ui.common

import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/** A form/list surface that follows its viewport width while retaining vertical scrolling. */
internal class ViewportWidthPanel : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) AdbToolboxTheme.Spacing.s5 else AdbToolboxTheme.Spacing.s4

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
