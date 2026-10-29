package io.github.dkej123.devicecockpit.intellij.currentapp

import io.github.dkej123.devicecockpit.intellij.ui.common.DesignIconButton
import io.github.dkej123.devicecockpit.intellij.ui.common.ShortcutHints
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppAction
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppDisplay
import io.github.dkej123.devicecockpit.application.currentapp.CurrentAppViewState
import io.github.dkej123.devicecockpit.domain.foreground.ProcessInfo
import io.github.dkej123.devicecockpit.domain.foreground.RuntimePermission
import io.github.dkej123.devicecockpit.domain.packages.AppIcon
import io.github.dkej123.devicecockpit.intellij.apps.DashedTopBorder
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.AppIconImages
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignSections
import io.github.dkej123.devicecockpit.intellij.ui.common.RoundedSurface
import io.github.dkej123.devicecockpit.intellij.ui.common.WrapRowLayout
import io.github.dkej123.devicecockpit.intellij.ui.common.VerticalStackLayout
import io.github.dkej123.devicecockpit.intellij.ui.common.flexRow
import io.github.dkej123.devicecockpit.intellij.ui.common.flexSpacer
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.time.Duration

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
    private val onResetPermission: (String, String) -> Unit,
    private val onApplyPending: () -> Unit,
    private val onWake: () -> Unit,
    private val onLaunchLast: (String) -> Unit,
) : JPanel(BorderLayout()) {

    // No "updated N s ago" meta: it flickered with every 3 s poll and told the user nothing (user decision, 2026-10-09).
    private val refreshButton = iconButton(AllIcons.Actions.Refresh, "Refresh now — also checks every 3 s while this view is visible") { onRefresh() }
    private val pendingLink = DesignButton("", DesignButtonStyle.LINK).apply {
        isVisible = false
        toolTipText = "Held while the pointer is over this section. Applies when you move away, or click to update now."
        addActionListener { onApplyPending() }
    }
    private val titleLabel = DesignSections.titleLabel("Current app")
    private val header = DesignSections.header(titleLabel, flexRow(0, pendingLink, refreshButton))

    private val tile = IconTile()
    private val labelLabel = JBLabel("").apply {
        font = DeviceCockpitTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(12f))
        foreground = DeviceCockpitTheme.Colors.text
    }
    private val tagLabel = TagLabel()
    private val packageLabel = JBLabel("").apply {
        font = DeviceCockpitTheme.Typography.mono.deriveFont(JBUIScale.scale(9.5f))
        foreground = DeviceCockpitTheme.Colors.textFaint
    }
    private val identityRow = flexRow(
        DeviceCockpitTheme.Spacing.s4,
        tile,
        JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(flexRow(DeviceCockpitTheme.Spacing.s3, labelLabel, tagLabel).apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(packageLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        },
    ).apply { border = JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset) }

    private val activityValue = factValue()
    private val versionValue = factValue()
    private val processValue = factValue()
    private val targetValue = factValue()
    private val activityCell = factCell("Activity", activityValue)
    private val factsGrid = JPanel(GridLayout(0, 3, DeviceCockpitTheme.Spacing.s4, DeviceCockpitTheme.Spacing.s4)).apply {
        isOpaque = false
        add(factCell("Version", versionValue))
        add(factCell("Process", processValue))
        add(factCell("Target SDK", targetValue))
    }
    private val factsPanel = JPanel(BorderLayout(0, DeviceCockpitTheme.Spacing.s4)).apply {
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
    private val processDeathButton = DesignButton("Process death", DesignButtonStyle.SECONDARY).apply {
        toolTipText = "Home → am kill → reopen: Android kills the app in the background and recreates it from saved state"
        addActionListener { target?.let { onAction(CurrentAppAction.ProcessDeath, it) } }
    }
    private val resetPermissionsButton = DesignButton("Reset permissions", DesignButtonStyle.SECONDARY).apply {
        toolTipText = "Revokes runtime permissions and clears “Don’t ask again” — the app is stopped, data stays"
        addActionListener { target?.let { onAction(CurrentAppAction.ResetPermissions, it) } }
    }
    /** Runtime permissions of the app shown, for the reset menu. */
    private var permissions: List<RuntimePermission> = emptyList()

    /** Reset permissions' caret: all granted permissions, or one of them (task 067). */
    private val resetMoreButton = iconButton(AllIcons.General.ChevronDown, "Reset one permission…") { requestResetMenu(it) }.apply { getAccessibleContext().accessibleName = "Choose permissions to reset" }

    private val detailsLink = AppToolsDetailsButton().apply {
        addActionListener { target?.let(onDetails) }
    }
    private val detailsRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset)
        add(detailsLink, BorderLayout.CENTER)
    }
    // The buttons wrap onto a second row in a narrow tool window.
    private val actionRow = JPanel(WrapRowLayout(DeviceCockpitTheme.Spacing.s3, DeviceCockpitTheme.Spacing.s2)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset)
        add(primaryButton)
        add(killButton)
        add(processDeathButton)
        add(flexRow(0, resetPermissionsButton, resetMoreButton))
    }

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
            DeviceCockpitTheme.Spacing.s3,
            JBLabel("DESTRUCTIVE").apply {
                font = DeviceCockpitTheme.Typography.groupLabel
                foreground = DeviceCockpitTheme.Colors.textFaint
            },
            spacer,
            clearDataButton,
            uninstallButton,
            fill = spacer,
        )
    }.apply {
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset),
            BorderFactory.createCompoundBorder(
                DashedTopBorder(DeviceCockpitTheme.Colors.border),
                JBUI.Borders.empty(DeviceCockpitTheme.Spacing.s4, DeviceCockpitTheme.Spacing.sectionInset, 0, 0),
            ),
        )
    }

    // Home / lock / error / reading collapse to the identity and a note row.
    private val noteLabel = JBLabel("").apply {
        font = DeviceCockpitTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f))
        foreground = DeviceCockpitTheme.Colors.textDim
    }
    private val noteLink = DesignButton("", DesignButtonStyle.LINK)
    private var noteAction: () -> Unit = {}
    private val noteRow = run {
        val spacer = flexSpacer()
        flexRow(DeviceCockpitTheme.Spacing.s4, noteLabel, spacer, noteLink, fill = spacer)
    }.apply {
        border = JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset)
        noteLink.addActionListener { noteAction() }
    }
    private val errorTitle = JBLabel("Couldn’t read the foreground app").apply {
        font = DeviceCockpitTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(11.5f))
        foreground = DeviceCockpitTheme.Colors.text
    }
    private val errorReason = JBLabel("").apply {
        font = DeviceCockpitTheme.Typography.mono.deriveFont(JBUIScale.scale(9.5f))
        foreground = DeviceCockpitTheme.Colors.textFaint
    }
    private val errorBlock = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset)
        add(errorTitle)
        add(errorReason)
    }
    private val skeleton = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset)
        listOf(46, 70).forEach { add(Bar(it)); add(javax.swing.Box.createVerticalStrut(JBUIScale.scale(6))) }
    }

    private val section = DesignSections.section().apply {
        layout = VerticalStackLayout { DeviceCockpitTheme.Spacing.s3 }
        listOf(header, skeleton, identityRow, errorBlock, factsPanel, helpLabel, actionRow, detailsRow, noteRow, destructiveZone).forEach(::add)
    }.also {
        DesignSections.makeCollapsible(it, "app", titleLabel)
        (it.components.last() as JPanel).layout = VerticalStackLayout { DeviceCockpitTheme.Spacing.s3 }
    }

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(section, BorderLayout.CENTER)
    }

    // The process time is read once per PID; between polls it keeps counting here, once a second.
    private var shownProcess: ProcessInfo? = null
    private val processTicker = javax.swing.Timer(1_000) { if (isShowing) showProcessTime() }

    private fun showProcessTime() {
        val process = shownProcess
        processValue.text = if (process == null) "not running" else "${process.pid} · ${process.runningNow()?.let(::formatElapsed) ?: "?"}"
    }

    // Runs only while the section is in a window, so a section never shown leaves no timer behind.
    private fun updateProcessTicker() {
        val process = shownProcess
        if (isDisplayable && process?.measuredAt != null && process.runningFor != null) processTicker.start() else processTicker.stop()
    }

    override fun addNotify() {
        super.addNotify()
        updateProcessTicker()
    }

    override fun removeNotify() {
        processTicker.stop()
        super.removeNotify()
    }

    internal fun tickForTest() = showProcessTime()

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun update(state: CurrentAppViewState) {
        val display = state.display
        val busy = state.busy
        updateMeta(state)
        listOf(skeleton, identityRow, errorBlock, factsPanel, helpLabel, actionRow, detailsRow, noteRow, destructiveZone).forEach { it.isVisible = false }
        target = null
        shownProcess = null
        processTicker.stop()
        when (display) {
            CurrentAppDisplay.Reading -> skeleton.isVisible = true
            is CurrentAppDisplay.App -> showApp(display, busy)
            is CurrentAppDisplay.Home -> {
                showPlaceholder("Home screen", display.launcherPackage)
                val last = display.lastApp
                if (last != null) {
                    note("Last app: ${identity(last)?.label ?: last}", "Launch", ShortcutHints.withAction("Launch $last", RESTART_FOREGROUND_ACTION)) { onLaunchLast(last) }
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
            listOf(primaryButton, killButton, processDeathButton, resetPermissionsButton, clearDataButton, uninstallButton).forEach { it.isEnabled = false }
        }
        revalidate()
        repaint()
        openResetMenuWhenRefreshed(state)
    }

    private fun updateMeta(state: CurrentAppViewState) {
        val pending = state.pendingPackage
        pendingLink.isVisible = pending != null
        if (pending != null) {
            val name = identity(pending)?.label ?: pending
            pendingLink.text = if (width in 1..339) "$name in front · Update" else "$name came to the front · Update"
        }
    }

    /** One entry of the Reset permissions popup. */
    internal class ResetChoice(
        val label: String,
        val description: String,
        val enabled: Boolean,
        val separatorAbove: Boolean,
        val run: () -> Unit,
    )

    private fun resetChoices(): List<ResetChoice> {
        val pkg = target ?: return emptyList()
        val granted = permissions.filter { it.granted }
        val all = ResetChoice(
            label = "All granted permissions (${granted.size})",
            description = "Revokes every runtime permission and clears “Don’t ask again”",
            enabled = true,
            separatorAbove = false,
        ) { onAction(CurrentAppAction.ResetPermissions, pkg) }
        return listOf(all) + granted.mapIndexed { index, permission ->
            ResetChoice(
                label = readablePermission(permission.name),
                description = if (permission.fixed) "${permission.name} — fixed by the system or a policy, can’t be revoked" else permission.name,
                enabled = !permission.fixed,
                separatorAbove = index == 0,
            ) { onResetPermission(pkg, permission.name) }
        }
    }

    /**
     * The menu lists permissions read when the app came to the front; ones granted since (App details, the phone)
     * were missing. The caret refreshes first and opens the menu on the first finished refresh after it.
     */
    private var resetMenuAnchor: JComponent? = null
    private var resetRefreshStarted = false
    internal var openResetMenuForTest: ((JComponent) -> Unit)? = null

    private fun requestResetMenu(anchor: JComponent) {
        resetMenuAnchor = anchor
        resetRefreshStarted = false
        onRefresh()
    }

    private fun openResetMenuWhenRefreshed(state: CurrentAppViewState) {
        val anchor = resetMenuAnchor ?: return
        if (state.refreshing) { resetRefreshStarted = true; return }
        if (!resetRefreshStarted) return
        resetMenuAnchor = null
        openResetMenuForTest?.invoke(anchor) ?: showResetPopup(anchor)
    }

    /** The IDE's own list popup, so it looks like every other chooser: title, separator, speed search. */
    private fun showResetPopup(anchor: JComponent) {
        val choices = resetChoices()
        if (choices.isEmpty()) return
        val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<ResetChoice>("Reset permissions", choices) {
            override fun getTextFor(value: ResetChoice) = value.label

            override fun isSelectable(value: ResetChoice) = value.enabled

            override fun getSeparatorAbove(value: ResetChoice) =
                if (value.separatorAbove) com.intellij.openapi.ui.popup.ListSeparator("One permission") else null

            override fun isSpeedSearchEnabled() = true

            override fun onChosen(selectedValue: ResetChoice, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? =
                doFinalStep { selectedValue.run() }
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
    }

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, action: (JButton) -> Unit): JButton = DesignIconButton(icon, tooltip).apply {
        addActionListener { action(this) }
    }

    private fun showApp(display: CurrentAppDisplay.App, busy: CurrentAppAction?) {
        val pkg = display.app.packageName
        target = pkg
        val details = display.snapshot.details
        permissions = details?.runtimePermissions.orEmpty()
        // Read from the device for this app; the Apps list only knows the apps it has loaded.
        val identity = display.snapshot.info?.let { AppIdentity(it.label ?: pkg, it.icon) } ?: identity(pkg)
        identityRow.isVisible = true
        factsPanel.isVisible = true
        actionRow.isVisible = true
        detailsRow.isVisible = true
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
            display.systemUi && display.snapshot.foreground !is io.github.dkej123.devicecockpit.domain.foreground.ForegroundState.SystemUi -> display.app.activity
            display.systemUi -> "NotificationShade (window)"
            else -> display.app.activity
        }
        activityValue.toolTipText = "$pkg/${display.app.activity}"
        versionValue.text = details?.let { "${it.versionName ?: "?"} (${it.versionCode ?: "?"})" } ?: "—"
        versionValue.toolTipText = details?.let { "versionName ${it.versionName} · versionCode ${it.versionCode}" }
        val process = display.snapshot.process
        shownProcess = process
        showProcessTime()
        updateProcessTicker()
        processValue.foreground = if (process == null) DeviceCockpitTheme.Colors.textFaint else DeviceCockpitTheme.Colors.text
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
        // The chord comes from the active keymap (ShortcutHints), never a hardcoded "⌥⇧⌘R".
        primaryButton.toolTipText = ShortcutHints.withAction(
            if (display.killed) "Launch the main activity" else "Force-stop, then launch the main activity",
            RESTART_FOREGROUND_ACTION,
        )
        killButton.text = if (busy == CurrentAppAction.Kill) "Killing…" else "Kill"
        killButton.toolTipText = if (display.killed) "Not running" else "am force-stop — leaves data intact"
        processDeathButton.text = if (busy == CurrentAppAction.ProcessDeath) "Killing in background…" else "Process death"
        resetPermissionsButton.text = if (busy == CurrentAppAction.ResetPermissions) "Resetting…" else "Reset permissions"
        detailsLink.toolTipText = "Open App details for $pkg — Deep links, Permissions, Shared prefs, Databases"

        val idle = busy == null
        val actionsOff = display.systemUi
        primaryButton.isEnabled = idle && !actionsOff
        killButton.isEnabled = idle && !actionsOff && !display.killed
        processDeathButton.isEnabled = idle && !actionsOff && !display.killed && display.snapshot.process != null
        resetPermissionsButton.isEnabled = idle && !actionsOff
        resetMoreButton.isEnabled = resetPermissionsButton.isEnabled
        clearDataButton.isEnabled = idle && !actionsOff
        uninstallButton.isEnabled = idle && !actionsOff && details?.system != true
        val systemReason = "Disabled for System UI — it draws the status bar, shade and lock screen"
        if (actionsOff) listOf(primaryButton, killButton, processDeathButton, resetPermissionsButton, clearDataButton, uninstallButton).forEach { it.toolTipText = systemReason }
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

    internal val metaTextForTest: String get() = if (pendingLink.isVisible) pendingLink.text else ""
    internal val refreshButtonForTest: JButton get() = refreshButton
    internal fun resetChoicesForTest(): List<ResetChoice> = resetChoices()
    internal val primaryButtonForTest: JButton get() = primaryButton
    internal val killButtonForTest: JButton get() = killButton
    internal val processDeathButtonForTest: JButton get() = processDeathButton
    internal val resetPermissionsButtonForTest: JButton get() = resetPermissionsButton
    internal val resetMoreButtonForTest: JButton get() = resetMoreButton
    internal val uninstallButtonForTest: JButton get() = uninstallButton
    internal val clearDataButtonForTest: JButton get() = clearDataButton
    internal val detailsLinkForTest: JButton get() = detailsLink
    internal val labelForTest: String get() = labelLabel.text
    internal val tileShowsAndroidRobotForTest: Boolean get() = tile.showsAndroidRobot
    internal val tagForTest: String? get() = tagLabel.text.takeIf { tagLabel.isVisible }
    internal val processForTest: String get() = processValue.text
    internal val activityForTest: String get() = activityValue.text
    internal val noteForTest: String? get() = noteLabel.text.takeIf { noteRow.isVisible }
    internal val noteLinkForTest: JButton get() = noteLink

    private companion object {
        const val UNINSTALL_TOOLTIP = "Removes the app and all its data. Cannot be undone."

        /** `android.permission.POST_NOTIFICATIONS` → "Post notifications". */
        fun readablePermission(name: String): String =
            name.substringAfterLast('.').lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

        fun factValue() = JBLabel("—").apply {
            font = DeviceCockpitTheme.Typography.mono.deriveFont(JBUIScale.scale(11f))
            foreground = DeviceCockpitTheme.Colors.text
        }

        fun factCell(key: String, value: JBLabel): JPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(key.uppercase()).apply {
                font = DeviceCockpitTheme.Typography.groupLabel
                foreground = DeviceCockpitTheme.Colors.textFaint
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
        private val robot = io.github.dkej123.devicecockpit.intellij.ui.common.AndroidRobotIcon(24)
        val showsAndroidRobot: Boolean get() = dashed

        init {
            preferredSize = Dimension(JBUIScale.scale(28), JBUIScale.scale(28))
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        fun set(icon: AppIcon?, debuggable: Boolean, dashed: Boolean, dimmed: Boolean) {
            this.icon = icon?.takeIf { AppIconImages.decode(it) != null }
            this.dashed = dashed
            this.dimmed = dimmed
            fill = if (dashed) null else if (debuggable) DeviceCockpitTheme.Colors.brandBg else DeviceCockpitTheme.Colors.header
            outline = if (dashed) null else if (debuggable) DeviceCockpitTheme.Colors.brandBorder else DeviceCockpitTheme.Colors.border
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (dimmed) g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.55f)
                val current = icon
                when {
                    // Home and lock screens are the system, not an app: the Android robot (user request, 2026-10-29).
                    dashed -> robot.paintIcon(this, g2, (width - robot.iconWidth) / 2, (height - robot.iconHeight) / 2)
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
            font = DeviceCockpitTheme.Typography.groupLabel.deriveFont(Font.BOLD, JBUIScale.scale(9f))
            border = JBUI.Borders.empty(1, 4)
            isVisible = false
        }

        fun set(tag: String?) {
            isVisible = tag != null
            text = tag.orEmpty()
            foreground = if (tag == "debug") DeviceCockpitTheme.Colors.brand else DeviceCockpitTheme.Colors.textDim
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
                        g2.color = DeviceCockpitTheme.Colors.brandBg
                        g2.fillRoundRect(0, 0, width - 1, height - 1, JBUIScale.scale(6), JBUIScale.scale(6))
                    }
                    g2.color = if (debug) DeviceCockpitTheme.Colors.brandBorder else DeviceCockpitTheme.Colors.border
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
                g2.color = DeviceCockpitTheme.Colors.header
                g2.fillRoundRect(0, 0, width * percent / 100, height, JBUIScale.scale(6), JBUIScale.scale(6))
            } finally {
                g2.dispose()
            }
        }
    }
}

private const val RESTART_FOREGROUND_ACTION = "AdbToolbox.RestartForegroundApp"
