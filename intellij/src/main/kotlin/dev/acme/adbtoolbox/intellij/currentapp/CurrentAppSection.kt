package dev.acme.adbtoolbox.intellij.currentapp

import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.currentapp.CurrentAppAction
import dev.acme.adbtoolbox.application.currentapp.CurrentAppDisplay
import dev.acme.adbtoolbox.application.currentapp.CurrentAppViewState
import dev.acme.adbtoolbox.domain.packages.AppIcon
import dev.acme.adbtoolbox.intellij.apps.DashedTopBorder
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.AppIconImages
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.DesignSections
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What the section shows of an app: its label and launcher icon when the Apps list resolved them. */
data class AppIdentity(val label: String, val icon: AppIcon?)

/**
 * Current app (task 064, design §3a): the first section of the Device view. Renders
 * [CurrentAppViewState]; every action reports the package it was pressed on, captured at press time.
 */
class CurrentAppSection(
    private val identity: (String) -> AppIdentity?,
    private val onAction: (CurrentAppAction, String) -> Unit,
    private val onDetails: (String) -> Unit,
    private val onRefresh: () -> Unit,
    private val onApplyPending: () -> Unit,
    private val onWake: () -> Unit,
    private val onLaunchLast: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : JPanel(BorderLayout()) {

    private val metaLabel = DesignSections.metaLabel(size = 9.5f).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Refresh now — also checks every 3 s while this view is visible"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onRefresh()
        })
    }
    private val pendingLink = DesignButton("", DesignButtonStyle.LINK).apply {
        isVisible = false
        toolTipText = "Held while the pointer is over this section. Applies when you move away, or click to update now."
        addActionListener { onApplyPending() }
    }
    private val header = DesignSections.header(DesignSections.titleLabel("Current app"), flexRow(0, metaLabel, pendingLink))

    private val tile = IconTile()
    private val labelLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(12f))
        foreground = AdbToolboxTheme.Colors.text
    }
    private val tagLabel = TagLabel()
    private val packageLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(9.5f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val identityRow = flexRow(
        AdbToolboxTheme.Spacing.s4,
        tile,
        JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(flexRow(AdbToolboxTheme.Spacing.s3, labelLabel, tagLabel).apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(packageLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        },
    ).apply { border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset) }

    private val activityValue = factValue()
    private val versionValue = factValue()
    private val processValue = factValue()
    private val targetValue = factValue()
    private val activityCell = factCell("Activity", activityValue)
    private val factsGrid = JPanel(GridLayout(0, 3, AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.s4)).apply {
        isOpaque = false
        add(factCell("Version", versionValue))
        add(factCell("Process", processValue))
        add(factCell("Target SDK", targetValue))
    }
    private val factsPanel = JPanel(BorderLayout(0, AdbToolboxTheme.Spacing.s4)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 10, 6, 10)
        add(activityCell, BorderLayout.NORTH)
        add(factsGrid, BorderLayout.CENTER)
    }
    private val helpLabel = DesignSections.helpText("")

    private var target: String? = null
    private val primaryButton = DesignButton("Restart", DesignButtonStyle.PRIMARY).apply {
        addActionListener { target?.let { onAction(if (text.startsWith("Launch")) CurrentAppAction.Launch else CurrentAppAction.Restart, it) } }
    }
    private val killButton = DesignButton("Kill", DesignButtonStyle.SECONDARY).apply {
        addActionListener { target?.let { onAction(CurrentAppAction.Kill, it) } }
    }
    private val resetPermissionsButton = DesignButton("Reset permissions", DesignButtonStyle.SECONDARY).apply {
        toolTipText = "Revokes runtime permissions and clears “Don’t ask again” — the app is stopped, data stays"
        addActionListener { target?.let { onAction(CurrentAppAction.ResetPermissions, it) } }
    }
    private val detailsLink = DesignButton("Details", DesignButtonStyle.LINK).apply {
        addActionListener { target?.let(onDetails) }
    }
    private val actionRow = run {
        val spacer = flexSpacer()
        flexRow(AdbToolboxTheme.Spacing.s3, primaryButton, killButton, resetPermissionsButton, spacer, detailsLink, fill = spacer)
    }.apply { border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset) }

    private val clearDataButton = DesignButton("Clear data", DesignButtonStyle.DANGER).apply {
        toolTipText = "Deletes databases, prefs and caches. Cannot be undone."
        addActionListener { target?.let { onAction(CurrentAppAction.ClearData, it) } }
    }
    private val uninstallButton = DesignButton("Uninstall", DesignButtonStyle.DANGER).apply {
        toolTipText = UNINSTALL_TOOLTIP
        addActionListener { target?.let { onAction(CurrentAppAction.Uninstall, it) } }
    }
    private val destructiveZone = run {
        val spacer = flexSpacer()
        flexRow(
            AdbToolboxTheme.Spacing.s3,
            JBLabel("DESTRUCTIVE").apply {
                font = AdbToolboxTheme.Typography.groupLabel
                foreground = AdbToolboxTheme.Colors.textFaint
            },
            spacer,
            clearDataButton,
            uninstallButton,
            fill = spacer,
        )
    }.apply {
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset),
            BorderFactory.createCompoundBorder(
                DashedTopBorder(AdbToolboxTheme.Colors.border),
                JBUI.Borders.empty(AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.sectionInset, 0, 0),
            ),
        )
    }

    // Home / lock / error / reading collapse to the identity and a note row.
    private val noteLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.textDim
    }
    private val noteLink = DesignButton("", DesignButtonStyle.LINK)
    private var noteAction: () -> Unit = {}
    private val noteRow = run {
        val spacer = flexSpacer()
        flexRow(AdbToolboxTheme.Spacing.s4, noteLabel, spacer, noteLink, fill = spacer)
    }.apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        noteLink.addActionListener { noteAction() }
    }
    private val errorTitle = JBLabel("Couldn’t read the foreground app").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(11.5f))
        foreground = AdbToolboxTheme.Colors.text
    }
    private val errorReason = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(9.5f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val errorBlock = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        add(errorTitle)
        add(errorReason)
    }
    private val skeleton = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        listOf(46, 70).forEach { add(Bar(it)); add(javax.swing.Box.createVerticalStrut(JBUIScale.scale(6))) }
    }

    private val section = DesignSections.section(header, skeleton, identityRow, errorBlock, factsPanel, helpLabel, actionRow, noteRow, destructiveZone)

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(section, BorderLayout.CENTER)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun update(state: CurrentAppViewState) {
        val display = state.display
        val busy = state.busy
        updateMeta(state)
        listOf(skeleton, identityRow, errorBlock, factsPanel, helpLabel, actionRow, noteRow, destructiveZone).forEach { it.isVisible = false }
        target = null
        when (display) {
            CurrentAppDisplay.Reading -> skeleton.isVisible = true
            is CurrentAppDisplay.App -> showApp(display, busy)
            is CurrentAppDisplay.Home -> {
                showPlaceholder("Home screen", display.launcherPackage)
                val last = display.lastApp
                if (last != null) {
                    note("Last app: ${identity(last)?.label ?: last}", "Launch", "Launch $last  ⌥⇧⌘R") { onLaunchLast(last) }
                } else {
                    note("No app in the foreground.", null, null) {}
                }
            }
            CurrentAppDisplay.Locked -> {
                showPlaceholder("Lock screen", "com.android.systemui")
                note("Unlock the device to act on the foreground app.", "Wake", "input keyevent KEYCODE_WAKEUP") { onWake() }
            }
            is CurrentAppDisplay.Error -> {
                errorBlock.isVisible = true
                errorReason.text = display.reason
                note("The rest of this view still works.", "Retry", null) { onRefresh() }
            }
        }
        if (!state.deviceOnline) {
            listOf(primaryButton, killButton, resetPermissionsButton, clearDataButton, uninstallButton).forEach { it.isEnabled = false }
        }
        revalidate()
        repaint()
    }

    private fun updateMeta(state: CurrentAppViewState) {
        val pending = state.pendingPackage
        pendingLink.isVisible = pending != null
        metaLabel.isVisible = pending == null
        if (pending != null) {
            val name = identity(pending)?.label ?: pending
            pendingLink.text = if (width in 1..339) "$name in front · Update" else "$name came to the front · Update"
            return
        }
        val display = state.display
        metaLabel.foreground = AdbToolboxTheme.Colors.textFaint
        metaLabel.text = when {
            display is CurrentAppDisplay.Error -> "adb error".also { metaLabel.foreground = AdbToolboxTheme.Colors.red }
            state.refreshing || display == CurrentAppDisplay.Reading -> "reading…"
            display is CurrentAppDisplay.App && display.killed -> "not running"
            else -> updated(state.updatedAtMillis)
        }
    }

    private fun updated(at: Long?): String {
        if (at == null) return "—"
        val seconds = (now() - at).milliseconds.inWholeSeconds
        return if (seconds < 2) "updated just now" else "updated $seconds s ago"
    }

    private fun showApp(display: CurrentAppDisplay.App, busy: CurrentAppAction?) {
        val pkg = display.app.packageName
        target = pkg
        val details = display.snapshot.details
        val identity = identity(pkg)
        identityRow.isVisible = true
        factsPanel.isVisible = true
        actionRow.isVisible = true
        destructiveZone.isVisible = true
        tile.set(identity?.icon, debuggable = details?.debuggable == true, dashed = false, dimmed = display.killed)
        labelLabel.text = if (display.systemUi) "System UI" else identity?.label ?: pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)
        tagLabel.set(
            when {
                display.systemUi || details?.system == true -> "system"
                details?.debuggable == true -> "debug"
                else -> null
            },
        )
        packageLabel.text = pkg
        packageLabel.toolTipText = pkg
        activityValue.text = when {
            display.killed -> "—"
            display.systemUi && display.snapshot.foreground !is dev.acme.adbtoolbox.domain.foreground.ForegroundState.SystemUi -> display.app.activity
            display.systemUi -> "NotificationShade (window)"
            else -> display.app.activity
        }
        activityValue.toolTipText = "$pkg/${display.app.activity}"
        versionValue.text = details?.let { "${it.versionName ?: "?"} (${it.versionCode ?: "?"})" } ?: "—"
        versionValue.toolTipText = details?.let { "versionName ${it.versionName} · versionCode ${it.versionCode}" }
        val process = display.snapshot.process
        processValue.text = if (process == null) "not running" else "${process.pid} · ${process.runningFor?.let(::formatElapsed) ?: "?"}"
        processValue.foreground = if (process == null) AdbToolboxTheme.Colors.textFaint else AdbToolboxTheme.Colors.text
        targetValue.text = details?.targetSdk?.toString() ?: "—"
        targetValue.toolTipText = details?.let { "targetSdkVersion ${it.targetSdk} · minSdk ${it.minSdk}" }

        helpLabel.isVisible = display.killed || display.systemUi
        helpLabel.text = if (display.systemUi) {
            "System UI draws the status bar, shade and lock screen. Actions are off so the device stays usable."
        } else {
            "Force-stopped. Stays here until another app comes to the front."
        }

        primaryButton.text = when {
            busy == CurrentAppAction.Restart -> "Restarting…"
            busy == CurrentAppAction.Launch -> "Launching…"
            display.killed -> "Launch"
            else -> "Restart"
        }
        primaryButton.toolTipText = if (display.killed) "Launch the main activity  ⌥⇧⌘R" else "Force-stop, then launch the main activity  ⌥⇧⌘R"
        killButton.text = if (busy == CurrentAppAction.Kill) "Killing…" else "Kill"
        killButton.toolTipText = if (display.killed) "Not running" else "am force-stop — leaves data intact"
        resetPermissionsButton.text = if (busy == CurrentAppAction.ResetPermissions) "Resetting…" else "Reset permissions"
        detailsLink.toolTipText = "Open App details for $pkg — Info, Shared prefs, Databases"

        val idle = busy == null
        val actionsOff = display.systemUi
        primaryButton.isEnabled = idle && !actionsOff
        killButton.isEnabled = idle && !actionsOff && !display.killed
        resetPermissionsButton.isEnabled = idle && !actionsOff
        clearDataButton.isEnabled = idle && !actionsOff
        uninstallButton.isEnabled = idle && !actionsOff && details?.system != true
        val systemReason = "Disabled for System UI — it draws the status bar, shade and lock screen"
        if (actionsOff) listOf(primaryButton, killButton, resetPermissionsButton, clearDataButton, uninstallButton).forEach { it.toolTipText = systemReason }
        uninstallButton.toolTipText = when {
            actionsOff -> systemReason
            details?.system == true -> "Preinstalled system app — adb can’t uninstall it"
            else -> UNINSTALL_TOOLTIP
        }
    }

    private fun showPlaceholder(label: String, pkg: String) {
        identityRow.isVisible = true
        tile.set(null, debuggable = false, dashed = true, dimmed = false)
        labelLabel.text = label
        tagLabel.set(null)
        packageLabel.text = pkg
    }

    private fun note(text: String, link: String?, tooltip: String?, action: () -> Unit) {
        noteRow.isVisible = true
        noteLabel.text = text
        noteLink.isVisible = link != null
        noteLink.text = link.orEmpty()
        noteLink.toolTipText = tooltip
        noteAction = action
    }

    internal val metaTextForTest: String get() = if (pendingLink.isVisible) pendingLink.text else metaLabel.text
    internal val primaryButtonForTest: JButton get() = primaryButton
    internal val killButtonForTest: JButton get() = killButton
    internal val uninstallButtonForTest: JButton get() = uninstallButton
    internal val clearDataButtonForTest: JButton get() = clearDataButton
    internal val detailsLinkForTest: JButton get() = detailsLink
    internal val labelForTest: String get() = labelLabel.text
    internal val tagForTest: String? get() = tagLabel.text.takeIf { tagLabel.isVisible }
    internal val processForTest: String get() = processValue.text
    internal val activityForTest: String get() = activityValue.text
    internal val noteForTest: String? get() = noteLabel.text.takeIf { noteRow.isVisible }
    internal val noteLinkForTest: JButton get() = noteLink

    private companion object {
        const val UNINSTALL_TOOLTIP = "Removes the app and all its data. Cannot be undone."

        fun factValue() = JBLabel("—").apply {
            font = AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(11f))
            foreground = AdbToolboxTheme.Colors.text
        }

        fun factCell(key: String, value: JBLabel): JPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(key.uppercase()).apply {
                font = AdbToolboxTheme.Typography.groupLabel
                foreground = AdbToolboxTheme.Colors.textFaint
                getAccessibleContext().accessibleName = key
            }, BorderLayout.NORTH)
            add(value, BorderLayout.CENTER)
        }

        fun formatElapsed(duration: Duration): String {
            val s = duration.inWholeSeconds
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m ${s % 60}s"
                s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
                else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
            }
        }
    }

    /** The 28px app tile: launcher icon, or the Apps-list fallback tile; dashed for home/lock; 55% when killed. */
    private class IconTile : RoundedSurface(null, null, radius = { JBUIScale.scale(6) }) {
        private var icon: AppIcon? = null
        private var dashed = false
        private var dimmed = false

        init {
            preferredSize = Dimension(JBUIScale.scale(28), JBUIScale.scale(28))
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        fun set(icon: AppIcon?, debuggable: Boolean, dashed: Boolean, dimmed: Boolean) {
            this.icon = icon?.takeIf { AppIconImages.decode(it) != null }
            this.dashed = dashed
            this.dimmed = dimmed
            fill = if (dashed) null else if (debuggable) AdbToolboxTheme.Colors.brandBg else AdbToolboxTheme.Colors.header
            outline = if (dashed) null else if (debuggable) AdbToolboxTheme.Colors.brandBorder else AdbToolboxTheme.Colors.border
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (dimmed) g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.55f)
                val current = icon
                when {
                    dashed -> {
                        g2.color = AdbToolboxTheme.Colors.borderStrong
                        g2.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(JBUIScale.scale(3f), JBUIScale.scale(2f)), 0f)
                        g2.drawRoundRect(1, 1, width - 3, height - 3, JBUIScale.scale(12), JBUIScale.scale(12))
                    }
                    current != null -> AppIconImages.paint(g2, current, 0, 0, width, height)
                    else -> super.paintComponent(g2)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** The Apps-list "debug" tag, or the connection-chip-style "system" tag. */
    private class TagLabel : JBLabel("") {
        init {
            font = AdbToolboxTheme.Typography.groupLabel.deriveFont(Font.BOLD, JBUIScale.scale(9f))
            border = JBUI.Borders.empty(1, 4)
            isVisible = false
        }

        fun set(tag: String?) {
            isVisible = tag != null
            text = tag.orEmpty()
            foreground = if (tag == "debug") AdbToolboxTheme.Colors.brand else AdbToolboxTheme.Colors.textDim
            toolTipText = when (tag) {
                "debug" -> "android:debuggable=true — debugger, run-as and Shared prefs work"
                "system" -> "Preinstalled on the system image"
                else -> null
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            if (text.isNotEmpty()) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val debug = text == "debug"
                    if (debug) {
                        g2.color = AdbToolboxTheme.Colors.brandBg
                        g2.fillRoundRect(0, 0, width - 1, height - 1, JBUIScale.scale(6), JBUIScale.scale(6))
                    }
                    g2.color = if (debug) AdbToolboxTheme.Colors.brandBorder else AdbToolboxTheme.Colors.border
                    g2.drawRoundRect(0, 0, width - 1, height - 1, JBUIScale.scale(6), JBUIScale.scale(6))
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }

    private class Bar(private val percent: Int) : JComponent() {
        init {
            preferredSize = Dimension(JBUIScale.scale(percent * 3), JBUIScale.scale(10))
            maximumSize = Dimension(Int.MAX_VALUE, JBUIScale.scale(10))
            alignmentX = Component.LEFT_ALIGNMENT
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = AdbToolboxTheme.Colors.header
                g2.fillRoundRect(0, 0, width * percent / 100, height, JBUIScale.scale(6), JBUIScale.scale(6))
            } finally {
                g2.dispose()
            }
        }
    }
}
