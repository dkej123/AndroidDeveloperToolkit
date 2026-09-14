package dev.acme.adbtoolbox.intellij.network

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import dev.acme.adbtoolbox.application.network.ProxyViewState
import dev.acme.adbtoolbox.domain.network.ProxyEndpoint
import dev.acme.adbtoolbox.domain.network.ProxyReadState
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Task 032's minimal Network view (`design/README.md` §6): host/port fields, "Use my computer IP",
 * Enable/Disable, an active-proxy banner, an error label, and a "Recent" list — purely structural,
 * mirroring [dev.acme.adbtoolbox.intellij.apps.AppsPanel]: no color, icon, spacing, or final
 * copy/layout is decided here. [onHostChange]/[onPortChange] fire on every keystroke (the
 * controller decides validity), [onUseComputerIp] backs the IP-fill link, [onEnable]/[onReset] back
 * the primary/secondary action, and [onSelectRecent] carries the exact clicked row's endpoint —
 * never constructing a command itself.
 */
class NetworkPanel(
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUseComputerIp: () -> Unit,
    onEnable: () -> Unit,
    onReset: () -> Unit,
    private val onSelectRecent: (ProxyEndpoint) -> Unit,
) : JBPanel<NetworkPanel>(BorderLayout()) {

    private val hostField = JBTextField().apply {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onHostChange(text)
            override fun removeUpdate(e: DocumentEvent) = onHostChange(text)
            override fun changedUpdate(e: DocumentEvent) = onHostChange(text)
        })
    }
    private val portField = JBTextField().apply {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onPortChange(text)
            override fun removeUpdate(e: DocumentEvent) = onPortChange(text)
            override fun changedUpdate(e: DocumentEvent) = onPortChange(text)
        })
    }
    private val useComputerIpLink = JButton("Use my computer IP").apply {
        addActionListener { onUseComputerIp() }
    }
    private var isActive = false
    private val enableButton = JButton("Enable proxy").apply {
        addActionListener { if (isActive) onReset() else onEnable() }
        isEnabled = false
    }
    private val resetLink = JButton("Reset").apply {
        addActionListener { onReset() }
        isVisible = false
    }
    private val errorLabel = JBLabel("").apply { isVisible = false }
    private val activeBannerLabel = JBLabel("").apply { isVisible = false }

    private val formRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(hostField)
        add(JBLabel(":"))
        add(portField)
    }
    private val actionRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(useComputerIpLink)
        add(enableButton)
        add(resetLink)
    }

    private val recentsModel = DefaultListModel<ProxyEndpoint>()
    private val recentsList = JBList(recentsModel).apply {
        addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                selectedValue?.let(onSelectRecent)
            }
        }
    }

    init {
        val header = JPanel(BorderLayout()).apply {
            add(formRow, BorderLayout.NORTH)
            add(actionRow, BorderLayout.CENTER)
            add(activeBannerLabel, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(errorLabel, BorderLayout.CENTER)
        add(recentsList, BorderLayout.SOUTH)
    }

    /** Test-only visibility hooks so a test can drive real Swing interactions without a live display. */
    internal val hostFieldForTest: JTextField get() = hostField
    internal val portFieldForTest: JTextField get() = portField
    internal val useComputerIpLinkForTest: JButton get() = useComputerIpLink
    internal val enableButtonForTest: JButton get() = enableButton
    internal val resetLinkForTest: JButton get() = resetLink
    internal val errorLabelForTest: JBLabel get() = errorLabel
    internal val activeBannerLabelForTest: JBLabel get() = activeBannerLabel
    internal val recentsListForTest: JBList<ProxyEndpoint> get() = recentsList

    fun update(state: ProxyViewState) {
        if (hostField.text != state.hostInput) hostField.text = state.hostInput
        if (portField.text != state.portInput) portField.text = state.portInput

        val active = state.readState as? ProxyReadState.Active
        isActive = active != null
        enableButton.text = if (active != null) "Disable" else "Enable proxy"
        enableButton.isEnabled = state.isDeviceEligible && !state.isBusy &&
            (if (active != null) true else state.canSubmit)
        resetLink.isVisible = active != null
        resetLink.isEnabled = state.isDeviceEligible && !state.isBusy

        activeBannerLabel.isVisible = active != null
        activeBannerLabel.text = active?.let { "All traffic routed through ${it.endpoint.render()}" } ?: ""

        val errorText = state.hostError ?: state.portError ?: state.error
        errorLabel.isVisible = errorText != null
        errorLabel.text = errorText ?: ""

        if (recentsModel.size() != state.recents.size || (0 until recentsModel.size()).any { recentsModel.get(it) != state.recents[it] }) {
            recentsModel.clear()
            state.recents.forEach(recentsModel::addElement)
        }
    }

    /** Test/disposal seam: no owned listeners today, kept for [dev.acme.adbtoolbox.intellij.apps.AppsPanel]-style symmetry. */
    fun disposePanel() = Unit
}
