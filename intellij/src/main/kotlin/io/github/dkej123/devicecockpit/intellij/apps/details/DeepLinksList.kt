package io.github.dkej123.devicecockpit.intellij.apps.details

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalysis
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkLaunchMemory
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkSource
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkTarget
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkVariant
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkVariants
import io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState
import io.github.dkej123.devicecockpit.domain.deeplinks.ParameterRequirement
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.WrappingText
import io.github.dkej123.devicecockpit.intellij.ui.common.flexRow
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The App details Deep Links tab body, styled like [PermissionsList]: a filter and Analyze APK on
 * top, then one row per link ([DeepLinkVariants]) grouped by activity — the template over its
 * parameters, chips for app-link state, source and warnings, Open… on the right. Opening a row
 * unfolds the [DeepLinkLauncherPanel] under it. The caller puts [content] in a scroll pane.
 */
class DeepLinksList(
    private val onAnalyze: () -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    memory: DeepLinkLaunchMemory? = null,
) {
    private var analysis: DeepLinkAnalysis? = null
    private var packageName = ""
    private var opened: DeepLinkVariant? = null
    private val rows = mutableListOf<Pair<DeepLinkVariant, LinkRow>>()

    private val launcher = DeepLinkLauncherPanel(onOpen, onCopy, memory).apply {
        background = DeviceCockpitTheme.Colors.accentBg
        isOpaque = true
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val filter = JBTextField().apply {
        name = "appDetailsDeepLinkSearch"
        emptyText.text = "Filter by link, activity or parameter"
        toolTipText = emptyText.text
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = rebuild()
            override fun removeUpdate(e: DocumentEvent?) = rebuild()
            override fun changedUpdate(e: DocumentEvent?) = rebuild()
        })
    }
    val analyze = DesignButton("Analyze APK", DesignButtonStyle.SECONDARY).apply {
        name = "appDetailsAnalyzeDeepLinks"
        toolTipText = "Reads the installed APK's manifest (and the project's Navigation graphs) for the links the app accepts"
        addActionListener { onAnalyze() }
    }
    private val status = WrappingText("", DeviceCockpitTheme.Typography.caption, DeviceCockpitTheme.Colors.textFaint).apply {
        border = JBUI.Borders.empty(0, DeviceCockpitTheme.Spacing.sectionInset, DeviceCockpitTheme.Spacing.s2, DeviceCockpitTheme.Spacing.sectionInset)
    }

    /** Filter, Analyze APK and the status line; goes above the scrolling [content]. */
    val header: JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(flexRow(DeviceCockpitTheme.Spacing.s3, filter, analyze, fill = filter, shrink = filter).apply {
            border = JBUI.Borders.empty(DeviceCockpitTheme.Spacing.s3, DeviceCockpitTheme.Spacing.sectionInset)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        add(status.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }

    /** The grouped rows (and the unfolded launcher); scrolls. */
    val content: JPanel = io.github.dkej123.devicecockpit.intellij.ui.common.ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = DeviceCockpitTheme.Colors.bg
    }

    fun render(analysis: DeepLinkAnalysis?, packageName: String, loading: Boolean, error: String?) {
        val changed = analysis != this.analysis || packageName != this.packageName
        this.analysis = analysis
        this.packageName = packageName
        analyze.isEnabled = !loading
        if (changed) rebuild()
        val links = rows.size
        status.text = when {
            loading -> "Reading the installed APK and split manifests…"
            error != null -> error
            analysis == null -> "Analysis runs only on demand. Cached results are restored when available."
            else -> "$links ${if (links == 1) "link" else "links"} in " +
                analysis.catalog.targets.size.let { n -> "$n ${if (n == 1) "activity" else "activities"}" } +
                " · ${if (analysis.fromCache) "cached" else "analyzed"} · open one to fill in its parameters"
        }
        status.foreground = if (error != null) DeviceCockpitTheme.Colors.red else DeviceCockpitTheme.Colors.textFaint
    }

    private fun rebuild() {
        val query = filter.text.trim().lowercase()
        content.removeAll()
        rows.clear()
        val current = analysis
        if (current != null) current.catalog.targets.forEach { target ->
            val variants = DeepLinkVariants.expand(target).filter { variant -> query.isEmpty() || matches(target, variant, query) }
            if (variants.isEmpty()) return@forEach
            content.add(groupLabel("${target.componentName.substringAfterLast('.')} · ${variants.size} ${if (variants.size == 1) "link" else "links"}", target.componentName))
            variants.forEach { variant ->
                val row = LinkRow(variant, chips(current, target, variant), expanded = variant == opened) { toggle(variant) }
                rows += variant to row
                content.add(row)
                if (variant == opened) content.add(launcher)
            }
        }
        if (rows.none { it.first == opened }) opened = null
        if (current != null && rows.isEmpty()) {
            content.add(JBLabel(if (query.isEmpty()) "This app declares no deep links" else "No link matches “${filter.text.trim()}”").apply {
                font = DeviceCockpitTheme.Typography.caption
                foreground = DeviceCockpitTheme.Colors.textFaint
                border = JBUI.Borders.empty(DeviceCockpitTheme.Spacing.s5, DeviceCockpitTheme.Spacing.sectionInset)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        content.revalidate()
        content.repaint()
    }

    private fun toggle(variant: DeepLinkVariant) {
        opened = if (opened == variant) null else variant
        launcher.show(opened, packageName)
        rebuild()
    }

    private fun matches(target: DeepLinkTarget, variant: DeepLinkVariant, query: String) =
        variant.template.lowercase().contains(query) ||
            target.componentName.lowercase().contains(query) ||
            variant.parameters.any { it.name.lowercase().contains(query) }

    private fun chips(analysis: DeepLinkAnalysis, target: DeepLinkTarget, variant: DeepLinkVariant): List<Tag> {
        val c = DeviceCockpitTheme.Colors
        val host = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://([^/:?#{]+)""").find(variant.template)?.groupValues?.get(1)
        val verification = host?.let { h -> analysis.verifications.firstOrNull { it.host == h } }
        return buildList {
            when (verification?.deviceState) {
                DeviceLinkState.VERIFIED -> add(Tag("Verified", c.green, c.greenBg).apply { toolTipText = "Android opens this app link without asking" })
                DeviceLinkState.APPROVED -> add(Tag("Approved", c.green, c.greenBg).apply { toolTipText = "The user chose this app for the domain" })
                DeviceLinkState.DENIED -> add(Tag("Denied", c.red, c.redBg).apply { toolTipText = "The user turned this domain off for the app" })
                null, DeviceLinkState.NONE -> if (variant.autoVerify) add(Tag("App link", c.amber, c.amberBg).apply { toolTipText = "autoVerify, but not verified on this device" })
                else -> add(Tag(verification.deviceState.name.lowercase().replace('_', ' '), c.textDim, c.hover))
            }
            if (variant.fromNavigation) add(Tag("Navigation", c.accent, c.accentBg).apply { toolTipText = "Template and parameters from the project's Navigation graph" })
            if (DeepLinkSource.RUNTIME_UNKNOWN in target.sources) add(Tag("Not in the APK", c.amber, c.amberBg).apply { toolTipText = "Only in the project — the installed build may not have it yet" })
            if (!target.hasDefaultCategory) add(Tag("No DEFAULT", c.amber, c.amberBg).apply { toolTipText = "The filter lacks android.intent.category.DEFAULT; implicit links may not reach it" })
            if (verification?.stale == true) add(Tag("Stale check", c.textDim, c.hover).apply { toolTipText = "Validated ${verification.validatedAtEpochMillis ?: "a while ago"}" })
            verification?.error?.let { add(Tag("Check failed", c.red, c.redBg).apply { toolTipText = it }) }
            variant.advancedPattern?.let { add(Tag("Regex path", c.textDim, c.hover).apply { toolTipText = "Path must match $it" }) }
        }
    }

    private fun groupLabel(text: String, tooltip: String) = object : JBLabel(text.uppercase()) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        font = DeviceCockpitTheme.Typography.groupLabel
        foreground = DeviceCockpitTheme.Colors.textFaint
        toolTipText = tooltip
        border = JBUI.Borders.empty(DeviceCockpitTheme.Spacing.s5, DeviceCockpitTheme.Spacing.sectionInset, DeviceCockpitTheme.Spacing.s2, DeviceCockpitTheme.Spacing.sectionInset)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    internal val templatesForTest: List<String> get() = rows.map { it.first.template }
    internal fun chipsForTest(template: String): List<String> = rows.first { it.first.template == template }.second.chips.map { it.text }
    internal fun parametersForTest(template: String): String = rows.first { it.first.template == template }.second.parameters.text
    internal fun openForTest(template: String) = rows.first { it.first.template == template }.second.open.doClick()
    internal val launcherForTest: DeepLinkLauncherPanel get() = launcher
    internal val launcherShownForTest: Boolean get() = launcher.parent == content
    internal var filterForTest: String
        get() = filter.text
        set(value) { filter.text = value }
}

/** One link: template and chips on the first line, its parameters below; Open… on the right. */
private class LinkRow(variant: DeepLinkVariant, val chips: List<Tag>, expanded: Boolean, onToggle: () -> Unit) : JPanel(BorderLayout()) {

    private val template = JBLabel(variant.template).apply {
        font = DeviceCockpitTheme.Typography.mono
        foreground = DeviceCockpitTheme.Colors.text
    }
    val parameters = JBLabel(
        if (variant.parameters.isEmpty()) "No parameters declared — add your own"
        else variant.parameters.joinToString("  ") { it.name + if (it.requirement == ParameterRequirement.REQUIRED) "*" else "" },
    ).apply {
        font = DeviceCockpitTheme.Typography.monoMeta
        foreground = DeviceCockpitTheme.Colors.textFaint
    }
    val open = DesignButton(if (expanded) "Close" else "Open…", DesignButtonStyle.SECONDARY).apply {
        name = "appDetailsDeepLinkOpen:${variant.template}"
        addActionListener { onToggle() }
    }

    init {
        isOpaque = expanded
        background = DeviceCockpitTheme.Colors.accentBg
        name = "appDetailsDeepLink:${variant.template}"
        toolTipText = variant.template
        alignmentX = Component.LEFT_ALIGNMENT
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(DeviceCockpitTheme.Colors.border, 0, 0, if (expanded) 0 else 1, 0),
            JBUI.Borders.empty(DeviceCockpitTheme.Spacing.s3, DeviceCockpitTheme.Spacing.sectionInset),
        )
        val text = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(flexRow(DeviceCockpitTheme.Spacing.s3, template, *chips.toTypedArray<JComponent>(), shrink = template).apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(DeviceCockpitTheme.Spacing.s1))
            add(parameters.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }
        add(text, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(DeviceCockpitTheme.Spacing.s4)
            add(open)
        }, BorderLayout.EAST)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle()
        })
        if (expanded) open.icon = AllIcons.General.ArrowUp
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}
