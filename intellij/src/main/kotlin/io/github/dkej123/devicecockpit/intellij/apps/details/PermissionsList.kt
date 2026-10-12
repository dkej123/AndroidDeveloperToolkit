package io.github.dkej123.devicecockpit.intellij.apps.details

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.appdetails.AppDetailsIntent
import io.github.dkej123.devicecockpit.domain.appdata.AppPermission
import io.github.dkej123.devicecockpit.domain.appdata.PermissionFlag
import io.github.dkej123.devicecockpit.domain.appdata.PermissionKind
import io.github.dkej123.devicecockpit.domain.appdata.PermissionState
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignIconButton
import io.github.dkej123.devicecockpit.intellij.ui.common.ViewportWidthPanel
import io.github.dkej123.devicecockpit.intellij.ui.common.flexRow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The App details Permissions tab: a filter and **Reset all** on top, then the runtime permissions —
 * short name over the dim full name, flag tags, a coloured state chip and Grant/Revoke plus a reset
 * button for the ones the user may change — and the install-time ones folded into one read-only
 * summary row that expands on click. A filter searches both groups and shows every match.
 */
class PermissionsList(private val onIntent: (AppDetailsIntent) -> Unit) : JPanel(BorderLayout()) {

    private data class Shown(val permissions: List<AppPermission>, val busy: String?, val filter: String, val installOpen: Boolean)

    private var permissions: List<AppPermission> = emptyList()
    private var busy: String? = null
    private var installOpen = false
    private var shown: Shown? = null

    private val filter = JBTextField().apply {
        name = "appDetailsPermissionsFilter"
        emptyText.text = "Filter permissions"
        toolTipText = "Filter permissions by name"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
    }
    private val resetAll = DesignButton("Reset all", DesignButtonStyle.SECONDARY).apply {
        name = "appDetailsPermissionsResetAll"
        toolTipText = "Revokes every runtime permission you granted and clears “Don’t ask again” — fixed ones stay"
        addActionListener { onIntent(AppDetailsIntent.ResetAllPermissions) }
    }
    private val runtimeHeader = groupLabel()
    private val installHeader = groupLabel().apply {
        name = "appDetailsPermissionsInstallToggle"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Granted at install; Android does not let these change"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                installOpen = !installOpen
                rebuild()
            }
        })
    }
    private val emptyLabel = JBLabel().apply {
        font = AdbToolboxTheme.Typography.caption
        foreground = AdbToolboxTheme.Colors.textFaint
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s5, AdbToolboxTheme.Spacing.sectionInset)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val rows = mutableMapOf<String, PermissionRow>()
    private val content = ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.bg
    }

    init {
        background = AdbToolboxTheme.Colors.bg
        add(
            flexRow(AdbToolboxTheme.Spacing.s3, filter, resetAll, fill = filter, shrink = filter).apply {
                border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.sectionInset)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun render(permissions: List<AppPermission>, busy: String?) {
        this.permissions = permissions
        this.busy = busy
        resetAll.isEnabled = busy == null && permissions.any { it.resettable }
        rebuild()
    }

    private fun rebuild() {
        val query = filter.text.trim()
        val next = Shown(permissions, busy, query, installOpen)
        if (next == shown) return
        shown = next

        val matching = permissions.filter { query.isEmpty() || matches(it, query) }
        val runtime = matching.filter { it.kind == PermissionKind.RUNTIME }
        val install = matching.filter { it.kind != PermissionKind.RUNTIME }
        val allInstall = permissions.filter { it.kind != PermissionKind.RUNTIME }

        content.removeAll()
        rows.clear()
        if (runtime.isNotEmpty()) {
            runtimeHeader.setGroup("Runtime · ${runtime.size}")
            content.add(runtimeHeader)
            runtime.forEach { addRow(it) }
        }
        if (install.isNotEmpty()) {
            val granted = allInstall.count { it.granted == true }
            val notGranted = allInstall.size - granted
            val counts = if (notGranted == 0) "$granted granted" else "$granted granted, $notGranted not granted"
            val open = installOpen || query.isNotEmpty()
            installHeader.setGroup("Install-time · $counts · read-only")
            installHeader.icon = if (open) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            content.add(installHeader)
            if (open) install.forEach { addRow(it) }
        }
        if (matching.isEmpty()) {
            emptyLabel.text = if (permissions.isEmpty()) "This app requests no permissions" else "No permission matches “$query”"
            content.add(emptyLabel)
        }
        content.revalidate()
        content.repaint()
    }

    private fun addRow(permission: AppPermission) {
        val row = PermissionRow(permission, busy, onIntent)
        rows[permission.name] = row
        content.add(row)
    }

    private fun matches(permission: AppPermission, query: String): Boolean =
        permission.name.contains(query, ignoreCase = true) || shortName(permission.name).contains(query, ignoreCase = true)

    private fun groupLabel() = object : JBLabel() {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        font = AdbToolboxTheme.Typography.groupLabel
        foreground = AdbToolboxTheme.Colors.textFaint
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s5, AdbToolboxTheme.Spacing.sectionInset, AdbToolboxTheme.Spacing.s2, AdbToolboxTheme.Spacing.sectionInset)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** Keeps the group text as written; the label shows it upper-cased like every group label. */
    private fun JBLabel.setGroup(value: String) {
        putClientProperty(GROUP_KEY, value)
        text = value.uppercase()
    }

    internal val visibleRowsForTest: List<String> get() = rows.keys.toList()
    internal val runtimeHeaderForTest: String get() = runtimeHeader.getClientProperty(GROUP_KEY) as String
    internal val installHeaderForTest: String get() = installHeader.getClientProperty(GROUP_KEY) as String
    internal val resetAllForTest: JButton get() = resetAll
    internal val emptyTextForTest: String get() = if (emptyLabel.parent != null) emptyLabel.text else ""
    internal var filterForTest: String
        get() = filter.text
        set(value) { filter.text = value }

    internal fun toggleInstallForTest() {
        installOpen = !installOpen
        rebuild()
    }

    internal fun shortNameForTest(name: String): String = rows.getValue(name).shortName.text
    internal fun stateForTest(name: String): String = rows.getValue(name).state.text
    internal fun flagsForTest(name: String): List<String> = rows.getValue(name).flags.map { it.text }
    internal fun actionForTest(name: String): JButton? = rows.getValue(name).action
    internal fun resetForTest(name: String): JButton? = rows.getValue(name).reset

    private companion object {
        const val GROUP_KEY = "permissionsGroup"
    }
}

/** `android.permission.ACCESS_FINE_LOCATION` → "Access fine location". */
internal fun shortName(permission: String): String =
    permission.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)

/** One permission: short name and state chip, the full name, flag tags; Grant/Revoke and reset on the right. */
private class PermissionRow(permission: AppPermission, busy: String?, onIntent: (AppDetailsIntent) -> Unit) : JPanel(BorderLayout()) {

    val shortName = JBLabel(shortName(permission.name)).apply {
        font = AdbToolboxTheme.Typography.body
        foreground = AdbToolboxTheme.Colors.text
    }
    private val fullName = JBLabel(permission.name).apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    val flags: List<Tag> = permission.flags.sortedBy { it.ordinal }.map { flag ->
        Tag(FLAG_LABELS.getValue(flag).first, AdbToolboxTheme.Colors.textDim, AdbToolboxTheme.Colors.hover).apply { toolTipText = FLAG_LABELS.getValue(flag).second }
    }
    val state: Tag = stateTag(permission, applying = busy == permission.name)
    val action: JButton? = if (!permission.mutable) null else {
        val granted = permission.granted == true
        DesignButton(if (granted) "Revoke" else "Grant", DesignButtonStyle.SECONDARY).apply {
            name = "appDetailsPermissionAction:${permission.name}"
            isEnabled = busy == null
            addActionListener {
                onIntent(if (granted) AppDetailsIntent.RevokePermission(permission.name) else AppDetailsIntent.GrantPermission(permission.name))
            }
        }
    }
    val reset: JButton? = if (!permission.mutable) null else {
        DesignIconButton(AllIcons.General.Reset, "Reset decision — revokes and clears “Don’t ask again”").apply {
            name = "appDetailsPermissionReset:${permission.name}"
            isEnabled = busy == null && permission.resettable
            addActionListener { onIntent(AppDetailsIntent.ResetPermission(permission.name)) }
        }
    }

    init {
        isOpaque = false
        name = "appDetailsPermission:${permission.name}"
        toolTipText = permission.name
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(AdbToolboxTheme.Colors.border, 0, 0, 1, 0),
            JBUI.Borders.empty(AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.sectionInset),
        )
        // Name and state on the first line, the full name below, flag tags under that; only the
        // actions sit to the right, so the names keep their width in a narrow tool window.
        val names = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(flexRow(AdbToolboxTheme.Spacing.s3, shortName, state, shrink = shortName).apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(fullName.apply { alignmentX = Component.LEFT_ALIGNMENT })
            if (flags.isNotEmpty()) {
                add(Box.createVerticalStrut(AdbToolboxTheme.Spacing.s1))
                add(flexRow(AdbToolboxTheme.Spacing.s2, *flags.toTypedArray<JComponent>()).apply { alignmentX = Component.LEFT_ALIGNMENT })
            }
        }
        add(names, BorderLayout.CENTER)
        if (action != null || reset != null) {
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, AdbToolboxTheme.Spacing.s2, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(AdbToolboxTheme.Spacing.s4)
                    action?.let(::add)
                    reset?.let(::add)
                    preferredSize = Dimension(ACTION_CELL_WIDTH() + AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Sizes.secondaryButton)
                },
                BorderLayout.EAST,
            )
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private companion object {
        val FLAG_LABELS = mapOf(
            PermissionFlag.USER_SET to ("user-set" to "The user answered the permission prompt"),
            PermissionFlag.USER_FIXED to ("user-fixed" to "“Don’t ask again” — the app can no longer prompt"),
            PermissionFlag.ONE_TIME to ("one-time" to "Granted only while the app is in use this time"),
            PermissionFlag.POLICY_FIXED to ("policy" to "Set by a device policy; cannot be changed here"),
            PermissionFlag.SYSTEM_FIXED to ("system" to "Fixed by the system; cannot be changed here"),
        )

        /** Wide enough for "Revoke" and the reset button, so state chips line up across rows. */
        val ACTION_CELL_WIDTH: () -> Int = {
            DesignButton("Revoke", DesignButtonStyle.SECONDARY).preferredSize.width + AdbToolboxTheme.Spacing.s2 * 2 + AdbToolboxTheme.Sizes.iconButton
        }

        fun stateTag(permission: AppPermission, applying: Boolean): Tag {
            val c = AdbToolboxTheme.Colors
            if (applying) return Tag("Applying…", c.accent, c.accentBg)
            return when (permission.state) {
                PermissionState.GRANTED -> Tag("Granted", c.green, c.greenBg)
                PermissionState.GRANTED_ONE_TIME -> Tag("Granted once", c.green, c.greenBg)
                PermissionState.DENIED -> Tag("Denied", c.red, c.redBg)
                // The user-fixed tag tells "don’t ask again" apart; the chip stays short in a narrow window.
                PermissionState.DENIED_PERMANENTLY -> Tag("Denied", c.red, c.redBg)
                PermissionState.FIXED_READ_ONLY -> Tag("Fixed", c.textDim, c.hover)
                PermissionState.NOT_REQUESTED -> Tag("Not granted", c.textFaint, c.hover)
                PermissionState.UNKNOWN -> Tag("Unknown", c.textFaint, c.hover)
            }
        }
    }
}

/** A small rounded label: state chips and flag tags. */
private class Tag(text: String, fg: Color, private val fill: Color) : JBLabel(text) {
    init {
        font = AdbToolboxTheme.Typography.caption
        foreground = fg
        border = JBUI.Borders.empty(JBUIScale.scale(1), AdbToolboxTheme.Spacing.s3)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            val arc = AdbToolboxTheme.Radii.chip * 2
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
