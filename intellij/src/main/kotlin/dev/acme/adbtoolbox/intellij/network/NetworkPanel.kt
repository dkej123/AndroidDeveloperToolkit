package dev.acme.adbtoolbox.intellij.network

import com.intellij.ui.scale.JBUIScale
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipChoice
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipRow
import dev.acme.adbtoolbox.domain.network.NetworkThrottle
import dev.acme.adbtoolbox.application.network.ThrottleAvailability
import dev.acme.adbtoolbox.application.network.NetworkThrottleViewState
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.network.ProxyViewState
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.DesignSections
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import dev.acme.adbtoolbox.intellij.ui.common.StatusDotIcon
import dev.acme.adbtoolbox.intellij.ui.common.ViewportWidthPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Task 047's final visual design for the Network view (`design/README.md` §6): a "Global HTTP
 * proxy" section (header state meta, host/port field row, inline validation, "Use my computer IP"
 * link, the primary Enable/Disable action, and an active banner with a Reset link) followed by a
 * "Recent" section, built on the same section/field/link primitives
 * [dev.acme.adbtoolbox.intellij.display.DisplayPanel] already established. Every rendered value —
 * the header state, the active banner, and the primary button's label — comes only from
 * [ProxyViewState.readState] (device-truth readback, task 030) or [ProxyViewState.isDeviceEligible],
 * never from the host/port text a user last typed, so the view can never visually claim a proxy
 * state the device has not actually confirmed. The live dot in the active banner is a static
 * [StatusDotIcon] rather than the prototype's CSS pulse animation — the same documented,
 * no-invented-behavior simplification for a supplied visual with no native Swing equivalent.
 */
class NetworkPanel(
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUseComputerIp: () -> Unit,
    onEnable: () -> Unit,
    onReset: () -> Unit,
    private val onSelectRecent: (ProxyEndpoint) -> Unit,
    onApplyThrottle: (NetworkThrottle) -> Unit = {},
) : JBPanel<NetworkPanel>(BorderLayout()) {

    // ---- Global HTTP proxy section (`design/README.md` §6.1) ----

    private val proxyTitleLabel = DesignSections.titleLabel("Global HTTP proxy")

    // `proxyStateStyle`: 10px/700 UI font, `textFaint` when off, amber when active.
    private val proxyStateLabel = JBLabel("—").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(10f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val proxyHeader = DesignSections.header(proxyTitleLabel, proxyStateLabel)

    private val hostField = monoField().apply {
        toolTipText = HOST_FIELD_TOOLTIP
        getAccessibleContext().accessibleName = "Proxy host"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onHostChange(text)
            override fun removeUpdate(e: DocumentEvent) = onHostChange(text)
            override fun changedUpdate(e: DocumentEvent) = onHostChange(text)
        })
    }
    private val colonLabel = JBLabel(":").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUIScale.scale(12f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val portField = monoField().apply {
        preferredSize = Dimension(JBUIScale.scale(66), AdbToolboxTheme.Sizes.field)
        getAccessibleContext().accessibleName = "Proxy port"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onPortChange(text)
            override fun removeUpdate(e: DocumentEvent) = onPortChange(text)
            override fun changedUpdate(e: DocumentEvent) = onPortChange(text)
        })
    }
    // `proxyFieldRowStyle`: host (flex) · mono ":" · 66px port, gap 5, `padding: 0 10px`.
    private val fieldRow = flexRow(JBUIScale.scale(5), hostField, colonLabel, portField, fill = hostField).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
    }

    private val fieldErrorLabel = errorLabel()

    private val useComputerIpLink = linkButton("Use my computer IP") { onUseComputerIp() }.apply {
        toolTipText = USE_COMPUTER_IP_TOOLTIP
    }
    private var isActive = false
    private val primaryProxyButton = DesignButton("Enable proxy", DesignButtonStyle.PRIMARY).apply {
        isEnabled = false
        addActionListener { if (isActive) onReset() else onEnable() }
    }

    // `actionRowStyle` + spacer: the link on the leading edge, the primary action on the trailing edge.
    private val actionRow = run {
        val spacer = flexSpacer()
        flexRow(AdbToolboxTheme.Spacing.s3, useComputerIpLink, spacer, primaryProxyButton, fill = spacer)
    }.apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
    }

    private val liveDotLabel = JBLabel(StatusDotIcon(AdbToolboxTheme.Colors.amber, filled = true))
    private val activeBannerTextLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUIScale.scale(11f))
        foreground = AdbToolboxTheme.Colors.amber
    }
    private val resetLink = linkButton("Reset") { onReset() }

    // `proxyActiveBannerStyle`: `padding: 6px 8px`, radius 5, `amberBg` + 1px amber, gap 7. The text
    // is the row's only flexible child, so it ellipsises before it can reach the Reset action.
    private val activeBanner = RoundedSurface(AdbToolboxTheme.Colors.amberBg, AdbToolboxTheme.Colors.amber).apply {
        layout = FlexRowLayout(JBUIScale.scale(7))
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.s4)
        add(liveDotLabel)
        add(activeBannerTextLabel, FlexRowLayout.FILL)
        add(resetLink)
    }
    private val activeBannerWrap = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
        add(activeBanner, BorderLayout.CENTER)
    }

    private val helpTextLabel = DesignSections.helpText("")

    private val proxySection = DesignSections.section(proxyHeader, fieldRow, fieldErrorLabel, actionRow, activeBannerWrap, helpTextLabel)

    // ---- Recent section (`design/README.md` §6.2) ----

    private val recentHeader = DesignSections.header(DesignSections.titleLabel("Recent"), null)

    private val recentsModel = DefaultListModel<ProxyEndpoint>()
    // `recentProxies[].rowStyle`: 26px rows, `padding: 0 10px`, mono 11px target on the leading edge.
    private val recentsList = JBList(recentsModel).apply {
        isOpaque = false
        border = JBUI.Borders.empty()
        fixedCellHeight = JBUIScale.scale(26)
        cellRenderer = ListCellRenderer<ProxyEndpoint> { _, value, _, _, _ ->
            JBLabel(value.render()).apply {
                font = AdbToolboxTheme.Typography.mono
                foreground = AdbToolboxTheme.Colors.text
                border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
            }
        }
        addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                selectedValue?.let(onSelectRecent)
            }
        }
    }

    private val recentSection = DesignSections.section(recentHeader, recentsList)

    // ---- Network throttling section (emulator console `network speed`/`delay`) ----

    private val throttleMetaLabel = DesignSections.metaLabel()
    private val throttleChipRow = PresetChipRow(
        choices = NetworkThrottle.entries.map { PresetChipChoice(it, it.label, isDefault = it == NetworkThrottle.Off) },
        selected = NetworkThrottle.Off,
    ).apply {
        onSelectionChanged = { throttle -> onApplyThrottle(throttle) }
    }
    private val throttleHelpLabel = DesignSections.helpText(THROTTLE_HELP_EMULATOR)
    private val throttleSection = DesignSections.section(
        DesignSections.header(DesignSections.titleLabel("Network throttling"), throttleMetaLabel),
        DesignSections.inset(throttleChipRow),
        throttleHelpLabel,
    )

    private val contentPanel = ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.bg
        add(proxySection)
        add(throttleSection)
        add(recentSection)
    }

    internal val throttleChipRowForTest: PresetChipRow<NetworkThrottle> get() = throttleChipRow
    internal val throttleMetaLabelForTest: JBLabel get() = throttleMetaLabel
    internal val throttleHelpForTest: javax.swing.text.JTextComponent get() = throttleHelpLabel

    /** Readback-driven like the proxy: the highlighted chip is what the emulator reports. */
    fun update(state: NetworkThrottleViewState) {
        val emulator = state.availability == ThrottleAvailability.Emulator
        val current = state.current
        throttleChipRow.chips.forEach { it.isEnabled = emulator && !state.busy }
        current?.let(throttleChipRow::setSelectedValue)
        throttleMetaLabel.text = when {
            !emulator -> "emulator only"
            state.busy -> "applying…"
            current != null -> if (current == NetworkThrottle.Off) "off" else current.label
            else -> state.customDescription ?: "—"
        }
        throttleMetaLabel.foreground = if (emulator && current != null && current != NetworkThrottle.Off) {
            AdbToolboxTheme.Colors.amber
        } else {
            AdbToolboxTheme.Colors.textFaint
        }
        throttleHelpLabel.text = when {
            state.error != null -> state.error
            state.availability == ThrottleAvailability.PhysicalDevice -> THROTTLE_HELP_PHYSICAL
            else -> THROTTLE_HELP_EMULATOR
        }
    }

    private val scrollPane = JScrollPane(contentPanel).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = AdbToolboxTheme.Spacing.s5
    }

    init {
        background = AdbToolboxTheme.Colors.bg
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Test-only visibility hooks so a test can drive real Swing interactions without a live display. */
    internal val hostFieldForTest: JTextField get() = hostField
    internal val portFieldForTest: JTextField get() = portField
    internal val useComputerIpLinkForTest: JButton get() = useComputerIpLink
    internal val enableButtonForTest: JButton get() = primaryProxyButton
    internal val resetLinkForTest: JButton get() = resetLink
    internal val errorLabelForTest: JBLabel get() = fieldErrorLabel
    internal val activeBannerLabelForTest: JBLabel get() = activeBannerTextLabel
    internal val proxyStateLabelForTest: JBLabel get() = proxyStateLabel
    internal val recentsListForTest: JBList<ProxyEndpoint> get() = recentsList

    fun update(state: ProxyViewState) {
        if (hostField.text != state.hostInput) hostField.text = state.hostInput
        if (portField.text != state.portInput) portField.text = state.portInput

        val active = state.readState as? ProxyReadState.Active
        isActive = active != null

        val portInvalid = state.portError != null
        portField.foreground = if (portInvalid) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.text
        portField.border = BorderFactory.createCompoundBorder(
            SolidChipBorder(
                if (portInvalid) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.borderStrong,
                radius = { AdbToolboxTheme.Radii.field },
            ),
            JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s3),
        )

        hostField.isEnabled = state.isDeviceEligible
        portField.isEnabled = state.isDeviceEligible
        hostField.toolTipText = HOST_FIELD_TOOLTIP.withDisabledReason(state.isDeviceEligible)

        proxyStateLabel.text = when {
            !state.isDeviceEligible -> "—"
            active != null -> "active"
            else -> "off"
        }
        proxyStateLabel.foreground = if (active != null) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint

        presentProxyButton(active != null)
        primaryProxyButton.isEnabled = state.isDeviceEligible && !state.isBusy && (active != null || state.canSubmit)

        activeBannerWrap.isVisible = active != null
        activeBannerTextLabel.isVisible = active != null
        activeBannerTextLabel.text = active?.let { "All traffic routed through ${it.endpoint.render()}" } ?: ""
        resetLink.isVisible = active != null
        resetLink.isEnabled = state.isDeviceEligible && !state.isBusy

        helpTextLabel.text = if (active != null) {
            "Survives reboot until reset. Some apps pin certificates and will still fail."
        } else {
            "Sets settings put global http_proxy. Recent targets are remembered per project."
        }

        val fieldError = state.hostError ?: state.portError ?: state.error
        fieldErrorLabel.text = fieldError ?: ""
        fieldErrorLabel.isVisible = fieldError != null

        useComputerIpLink.isEnabled = state.isDeviceEligible && !state.isBusy && !state.isResolvingIp
        useComputerIpLink.text = if (state.isResolvingIp) "Resolving…" else "Use my computer IP"
        useComputerIpLink.toolTipText = USE_COMPUTER_IP_TOOLTIP.withDisabledReason(state.isDeviceEligible)

        if (recentsModel.size() != state.recents.size || (0 until recentsModel.size()).any { recentsModel.get(it) != state.recents[it] }) {
            recentsModel.clear()
            state.recents.forEach(recentsModel::addElement)
        }
    }

    private fun presentProxyButton(active: Boolean) {
        primaryProxyButton.text = if (active) "Disable" else "Enable proxy"
        primaryProxyButton.style = if (active) DesignButtonStyle.SECONDARY else DesignButtonStyle.PRIMARY
    }

    /** Test/disposal seam: no owned listeners today, kept for [dev.acme.adbtoolbox.intellij.apps.AppsPanel]-style symmetry. */
    fun disposePanel() = Unit

    private companion object {
        const val HOST_FIELD_TOOLTIP = "host or IP"
        const val USE_COMPUTER_IP_TOOLTIP = "Fills your machine's LAN address"

        /** `design/README.md` Interactions: every device-mutating control "keeps its tooltip and
         * gains the reason" it is disabled — the same [dev.acme.adbtoolbox.intellij.apps
         * .AppsPanel.disabledReason] extension, scoped to this view's single "no eligible device"
         * blocker. */
        fun String.withDisabledReason(isDeviceEligible: Boolean): String =
            if (isDeviceEligible) this else "$this — Connect a device to use this"

        fun monoField() = JBTextField().apply {
            font = AdbToolboxTheme.Typography.mono
            background = AdbToolboxTheme.Colors.field
            preferredSize = Dimension(preferredSize.width, AdbToolboxTheme.Sizes.field)
            border = BorderFactory.createCompoundBorder(
                SolidChipBorder(AdbToolboxTheme.Colors.borderStrong, radius = { AdbToolboxTheme.Radii.field }),
                JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s3),
            )
        }

        fun errorLabel() = JBLabel("").apply {
            font = AdbToolboxTheme.Typography.caption.deriveFont(JBUIScale.scale(10.5f))
            foreground = AdbToolboxTheme.Colors.red
            isVisible = false
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        }

        fun linkButton(text: String, onClick: () -> Unit) = DesignButton(text, DesignButtonStyle.LINK).apply {
            addActionListener { onClick() }
        }
    }
}

private const val THROTTLE_HELP_EMULATOR =
    "Slows the emulator's connection (speed and latency). It stays throttled until you pick Off or restart the emulator."
private const val THROTTLE_HELP_PHYSICAL =
    "Only emulators can be throttled: Android has no throttling command for physical devices without root."
