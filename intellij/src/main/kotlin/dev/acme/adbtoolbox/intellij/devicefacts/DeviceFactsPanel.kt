package dev.acme.adbtoolbox.intellij.devicefacts

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactId
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactState
import dev.acme.adbtoolbox.domain.devicefacts.DeviceFactValue
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.CenteredWrappingText
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.DesignSections
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.ViewportWidthPanel
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

private fun label(factId: DeviceFactId): String = when (factId) {
    DeviceFactId.AndroidVersion -> "Android"
    DeviceFactId.Resolution -> "Resolution"
    DeviceFactId.Density -> "Density"
    DeviceFactId.Battery -> "Battery"
    DeviceFactId.Abi -> "ABI"
    DeviceFactId.Uptime -> "Uptime"
}

private fun valueText(state: DeviceFactState?): String = when (state) {
    null, DeviceFactState.Loading -> "Loading…"
    is DeviceFactState.Unavailable -> "Unavailable"
    is DeviceFactState.Available -> valueText(state.value)
}

private fun valueText(value: DeviceFactValue): String = when (value) {
    is DeviceFactValue.AndroidVersion -> "${value.release} · API ${value.sdk}"
    is DeviceFactValue.Resolution -> "${value.widthPx}×${value.heightPx}"
    is DeviceFactValue.Density -> "${value.dpi} dpi"
    is DeviceFactValue.Battery -> "${value.levelPercent}% · ${if (value.charging) "charging" else "not charging"}"
    is DeviceFactValue.Abi -> value.value
    is DeviceFactValue.Uptime -> {
        val minutes = value.duration.inWholeMinutes
        "${minutes / 60}h ${minutes % 60}m"
    }
}

/** Task 044's final Device surface. It owns visual composition only; mounted feature views keep
 * forwarding the same presentation intents as before. */
class DeviceFactsPanel(
    private val onCopyReport: () -> Unit,
    onRefresh: () -> Unit = {},
    onPairOverWifi: () -> Unit = {},
) : JBPanel<DeviceFactsPanel>(CardLayout()) {

    private val cards: CardLayout get() = layout as CardLayout

    // Slot rows carry the sections' 10px inset. Mirroring fills the row (its help text wraps to
    // the section width); capture and device actions are `actionRowStyle` rows with a 6px gap.
    val mirroringSlot: JBPanel<Nothing> = slot(BorderLayout())
    val captureSlot: JBPanel<Nothing> = slot(FlexRowLayout(AdbToolboxTheme.Spacing.s3))
    val deviceActionsSlot: JBPanel<Nothing> = slot(FlexRowLayout(AdbToolboxTheme.Spacing.s3))

    private val factLabels: Map<DeviceFactId, JBLabel> = DeviceFactId.entries.associateWith {
        JBLabel("Loading…").apply {
            font = AdbToolboxTheme.Typography.mono
            foreground = AdbToolboxTheme.Colors.text
        }
    }

    val copyReportButton = linkButton("Copy report") { onCopyReport() }.apply { isEnabled = false }

    private val factsGrid = JBPanel<Nothing>(GridLayout(0, 3, AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.s4)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 10, 6, 10)
        DeviceFactId.entries.forEach { factId -> add(factCell(factId, factLabels.getValue(factId))) }
    }

    // Sections stack at their preferred heights from the top (NORTH), and the column follows the
    // viewport width so section meta, facts and help text never extend past the visible area.
    private val contentPanel = ViewportWidthPanel().apply {
        layout = BorderLayout()
        background = AdbToolboxTheme.Colors.bg
        add(JBPanel<Nothing>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(section("Mirroring", "scrcpy 2.7", mirroringSlot))
            add(section("Capture", "~/Desktop", captureSlot))
            add(deviceSection())
        }, BorderLayout.NORTH)
    }

    private val contentScroll = JScrollPane(
        contentPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        border = BorderFactory.createEmptyBorder()
        viewport.background = AdbToolboxTheme.Colors.bg
    }

    // `emptyBodyStyle`: 11px `textDim`, centered, `max-width: 250px`; narrower columns wrap it to
    // their own content width.
    private val emptyBodyLabel = CenteredWrappingText(
        EMPTY_BODY,
        AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(11f)),
        AdbToolboxTheme.Colors.textDim,
        maxWidth = { JBUI.scale(250) },
    )

    private val emptyPanel = emptyState(onRefresh, onPairOverWifi)
    private val skeletonBars = List(6) { index -> SkeletonBar(SKELETON_WIDTHS[index]) }
    private val loadingPanel = JBPanel<Nothing>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.bg
        border = JBUI.Borders.empty(12)
        skeletonBars.forEach { bar ->
            add(bar)
            add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s4))
        }
        add(Box.createVerticalGlue())
    }

    internal val visibleSkeletonCount: Int get() = if (loadingPanel.isVisible) skeletonBars.count { it.isVisible } else 0
    internal val factColumnCount: Int get() = (factsGrid.layout as GridLayout).columns

    init {
        background = AdbToolboxTheme.Colors.bg
        add(contentScroll, CONTENT)
        add(emptyPanel, EMPTY)
        add(loadingPanel, LOADING)
        cards.show(this, EMPTY)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyResponsiveLayout(width)
        })
    }

    fun update(state: DeviceFactsViewState) {
        val snapshot = when (state) {
            is DeviceFactsViewState.Connected -> state.snapshot
            is DeviceFactsViewState.Partial -> state.snapshot
            else -> null
        }
        DeviceFactId.entries.forEach { factId ->
            factLabels.getValue(factId).text = valueText(snapshot?.facts?.get(factId))
        }
        copyReportButton.isEnabled = snapshot != null
        cards.show(
            this,
            when (state) {
                DeviceFactsViewState.Loading -> LOADING
                is DeviceFactsViewState.Connected, is DeviceFactsViewState.Partial -> CONTENT
                DeviceFactsViewState.NoDevice, is DeviceFactsViewState.RecoverableError -> EMPTY
            },
        )
    }

    internal fun applyResponsiveLayout(width: Int) {
        val columns = if (width < AdbToolboxTheme.Breakpoints.narrow) 2 else 3
        val current = factsGrid.layout as GridLayout
        if (current.columns != columns) {
            factsGrid.layout = GridLayout(0, columns, AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.s4)
            factsGrid.revalidate()
        }
    }

    private fun section(title: String, meta: String, body: JComponent): JPanel =
        DesignSections.section(DesignSections.header(DesignSections.titleLabel(title), DesignSections.metaLabel(meta, 9.5f)), body)

    // The Device header keeps "Copy report" next to its title (`sectionHeaderStyle` gap 8, no spacer).
    private fun deviceSection(): JPanel = DesignSections.section(
        flexRow(AdbToolboxTheme.Spacing.s4, DesignSections.titleLabel("Device"), copyReportButton).apply {
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        },
        factsGrid,
        deviceActionsSlot,
    )

    private fun factCell(id: DeviceFactId, value: JBLabel): JPanel = JBPanel<Nothing>(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel(label(id).uppercase()).apply {
            font = AdbToolboxTheme.Typography.groupLabel
            foreground = AdbToolboxTheme.Colors.textFaint
        }, BorderLayout.NORTH)
        add(value, BorderLayout.CENTER)
    }

    private fun emptyState(onRefresh: () -> Unit, onPairOverWifi: () -> Unit): JPanel {
        val column = JBPanel<Nothing>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(34, 16, 16, 16)
        }
        fun centered(component: JComponent): JComponent = component.apply { alignmentX = Component.CENTER_ALIGNMENT }

        // `emptyWrapStyle` gap 6 between every item; the glyph adds `margin-bottom: 2`, the
        // actions `margin-top: 4` and the footer `margin-top: 8`.
        column.add(centered(EmptyDeviceGlyph()))
        column.add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3 + AdbToolboxTheme.Spacing.s1))
        column.add(centered(JBLabel("No device connected").apply {
            font = AdbToolboxTheme.Typography.sectionTitle
            foreground = AdbToolboxTheme.Colors.text
        }))
        column.add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3))
        column.add(centered(emptyBodyLabel))
        column.add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3 + AdbToolboxTheme.Spacing.s2))
        column.add(centered(flexRow(
            AdbToolboxTheme.Spacing.s3,
            DesignButton("Refresh", DesignButtonStyle.PRIMARY).apply { addActionListener { onRefresh() } },
            DesignButton("Pair over Wi-Fi…", DesignButtonStyle.SECONDARY).apply { addActionListener { onPairOverWifi() } },
        ).apply { maximumSize = preferredSize }))
        column.add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s3 + AdbToolboxTheme.Spacing.s4))
        column.add(centered(JBLabel("adb 35.0.2 · /opt/homebrew/bin/adb").apply {
            font = AdbToolboxTheme.Typography.monoMeta
            foreground = AdbToolboxTheme.Colors.textFaint
        }))
        return JBPanel<Nothing>(BorderLayout()).apply {
            background = AdbToolboxTheme.Colors.bg
            add(column, BorderLayout.NORTH)
        }
    }

    private companion object {
        const val CONTENT = "content"
        const val EMPTY = "empty"
        const val LOADING = "loading"
        const val EMPTY_BODY = "Connect over USB with USB debugging enabled, or pair wirelessly. Actions stay disabled until a device is online."
        val SKELETON_WIDTHS = intArrayOf(62, 88, 40, 74, 54, 82)
    }
}

private fun slot(layout: java.awt.LayoutManager): JBPanel<Nothing> = JBPanel<Nothing>(layout).apply {
    isOpaque = false
    border = JBUI.Borders.empty(0, 10)
}

private fun linkButton(text: String, action: () -> Unit): JButton = DesignButton(text, DesignButtonStyle.LINK).apply {
    addActionListener { action() }
}

private class SkeletonBar(private val widthPercent: Int) : JComponent() {
    init {
        preferredSize = Dimension(JBUI.scale(widthPercent * 3), JBUI.scale(10))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(10))
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        try {
            copy.color = AdbToolboxTheme.Colors.header
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            copy.fillRoundRect(0, 0, width * widthPercent / 100, height, JBUI.scale(6), JBUI.scale(6))
        } finally {
            copy.dispose()
        }
    }
}

private class EmptyDeviceGlyph : JComponent() {
    init {
        preferredSize = Dimension(JBUI.scale(26), JBUI.scale(34))
        maximumSize = preferredSize
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        try {
            // `emptyIconStyle`: radius 5, 1.6px `borderStrong` outline.
            copy.color = AdbToolboxTheme.Colors.borderStrong
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val stroke = JBUI.scale(1.6f)
            copy.stroke = java.awt.BasicStroke(stroke)
            val arc = AdbToolboxTheme.Radii.button * 2f
            copy.draw(java.awt.geom.RoundRectangle2D.Float(stroke / 2, stroke / 2, width - stroke, height - stroke, arc, arc))
        } finally {
            copy.dispose()
        }
    }
}
