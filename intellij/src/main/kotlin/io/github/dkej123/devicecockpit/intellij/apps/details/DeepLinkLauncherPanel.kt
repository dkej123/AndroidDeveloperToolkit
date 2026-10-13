package io.github.dkej123.devicecockpit.intellij.apps.details

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkVariant
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkVariants
import io.github.dkej123.devicecockpit.domain.deeplinks.ParameterLocation
import io.github.dkej123.devicecockpit.domain.deeplinks.ParameterRequirement
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButton
import io.github.dkej123.devicecockpit.intellij.ui.common.DesignButtonStyle
import io.github.dkej123.devicecockpit.intellij.ui.common.DeviceCockpitTheme
import io.github.dkej123.devicecockpit.intellij.ui.common.WrappingText
import io.github.dkej123.devicecockpit.intellij.ui.common.flexRow
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Opens the deep link variant selected in App details › Deep Links: one field per parameter its
 * template declares (prefilled with Navigation defaults), any number of extra query parameters, a
 * live preview of the URI, then Open (`am start -a VIEW -d`, through [onOpen]), Copy URI and Copy
 * adb command ([onCopy]).
 */
class DeepLinkLauncherPanel(
    private val onOpen: (String) -> Unit,
    private val onCopy: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private var variant: DeepLinkVariant? = null
    private var packageName: String = ""
    private val fields = linkedMapOf<String, JBTextField>()
    /** Values typed for this app, by parameter name, so they carry over to other links and back. */
    private val remembered = mutableMapOf<String, String>()
    private val extras = mutableListOf<Pair<JBTextField, JBTextField>>()

    private val title = JBLabel().apply { font = DeviceCockpitTheme.Typography.mono.deriveFont(java.awt.Font.BOLD) }
    private val hint = WrappingText("", DeviceCockpitTheme.Typography.caption, DeviceCockpitTheme.Colors.textDim)
    private val parametersGrid = JPanel(GridBagLayout()).apply { isOpaque = false }
    private val extrasPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val addParameter = DesignButton("Add parameter", DesignButtonStyle.SECONDARY).apply {
        name = "deepLinkAddParameter"
        toolTipText = "Adds a query parameter (?key=value)"
        addActionListener { addExtra("", "") }
    }
    private val preview = WrappingText("", DeviceCockpitTheme.Typography.mono, DeviceCockpitTheme.Colors.text)
    private val missing = WrappingText("", DeviceCockpitTheme.Typography.caption, DeviceCockpitTheme.Colors.amber)
    private val open = DesignButton("Open", DesignButtonStyle.PRIMARY).apply {
        name = "deepLinkOpen"
        addActionListener { uri()?.let(onOpen) }
    }
    private val copyUri = DesignButton("Copy URI", DesignButtonStyle.SECONDARY).apply {
        addActionListener { uri()?.let(onCopy) }
    }
    private val copyAdb = DesignButton("Copy adb command", DesignButtonStyle.SECONDARY).apply {
        addActionListener { uri()?.let { onCopy(adbCommand(it)) } }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(DeviceCockpitTheme.Spacing.s3, DeviceCockpitTheme.Spacing.sectionInset)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(title, hint, parametersGrid, extrasPanel, flexRow(DeviceCockpitTheme.Spacing.s3, addParameter), preview, missing).forEach {
                (it as java.awt.Component).let { c -> (c as javax.swing.JComponent).alignmentX = Component.LEFT_ALIGNMENT }
                add(it)
            }
            add(flexRow(DeviceCockpitTheme.Spacing.s3, open, copyUri, copyAdb).apply { alignmentX = Component.LEFT_ALIGNMENT })
        }, BorderLayout.CENTER)
        show(null, "")
    }

    /** Shows [selected] (or a prompt to pick one) for [pkg]; typed values are kept per parameter name. */
    fun show(selected: DeepLinkVariant?, pkg: String) {
        if (pkg != packageName) remembered.clear() else fields.forEach { (name, field) -> remembered[name] = field.text }
        val previous = remembered.toMap()
        variant = selected
        packageName = pkg
        fields.clear()
        extras.clear()
        parametersGrid.removeAll()
        extrasPanel.removeAll()
        val enabled = selected != null
        listOf(open, copyUri, copyAdb, addParameter).forEach { it.isEnabled = enabled }
        if (selected == null) {
            title.text = "Select a deep link to open it"
            hint.text = "Each row is one link the app accepts. Parameters it declares get a field here; add any others yourself."
            preview.text = ""
            missing.text = ""
            refreshLayout()
            return
        }
        title.text = selected.template
        hint.text = buildString {
            append(selected.componentName.substringAfterLast('.'))
            append(if (selected.fromNavigation) " · parameters from the Navigation graph" else " · parameters inferred from the manifest")
            selected.advancedPattern?.let { append(" · path must match $it") }
        }
        selected.parameters.forEachIndexed { row, parameter ->
            val label = JBLabel(buildString {
                append(parameter.name)
                if (parameter.requirement == ParameterRequirement.REQUIRED) append(" *")
            }).apply {
                font = DeviceCockpitTheme.Typography.mono
                toolTipText = listOfNotNull(
                    parameter.location.name.lowercase() + " parameter",
                    parameter.type?.let { "type $it" },
                    parameter.defaultValue?.let { "default $it" },
                    if (parameter.requirement == ParameterRequirement.REQUIRED) "required" else "optional",
                ).joinToString(" · ")
            }
            val field = JBTextField(previous[parameter.name] ?: parameter.defaultValue.orEmpty()).apply {
                name = "deepLinkParam_${parameter.name}"
                emptyText.text = listOfNotNull(parameter.type, if (parameter.location == ParameterLocation.QUERY) "query" else null)
                    .joinToString(" · ").ifEmpty { "value" }
                onChange { refreshPreview() }
            }
            fields[parameter.name] = field
            parametersGrid.add(label, GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 0, 2, DeviceCockpitTheme.Spacing.s3)
            })
            parametersGrid.add(field, GridBagConstraints().apply {
                gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(2, 0)
            })
        }
        refreshPreview()
        refreshLayout()
    }

    private fun addExtra(key: String, value: String) {
        val keyField = JBTextField(key, 12).apply { emptyText.text = "key"; onChange { refreshPreview() } }
        val valueField = JBTextField(value).apply { emptyText.text = "value"; onChange { refreshPreview() } }
        val pair = keyField to valueField
        extras += pair
        lateinit var row: JPanel
        val remove = DesignButton("✕", DesignButtonStyle.LINK).apply {
            toolTipText = "Remove this parameter"
            addActionListener {
                extras.remove(pair)
                extrasPanel.remove(row)
                refreshPreview()
                refreshLayout()
            }
        }
        row = flexRow(DeviceCockpitTheme.Spacing.s2, keyField, JBLabel("="), valueField, remove, fill = valueField).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(JBUIScale.scale(2))
        }
        extrasPanel.add(row)
        refreshPreview()
        refreshLayout()
        keyField.requestFocusInWindow()
    }

    private fun built() = variant?.let { selected ->
        DeepLinkVariants.build(
            selected.template,
            fields.mapValues { it.value.text.trim() },
            extras.map { it.first.text.trim() to it.second.text.trim() },
            optional = selected.parameters.filter { it.requirement != ParameterRequirement.REQUIRED }.map { it.name }.toSet(),
        )
    }

    private fun uri(): String? = built()?.takeIf { it.missing.isEmpty() }?.uri

    private fun refreshPreview() {
        val result = built() ?: return
        preview.text = result.uri
        missing.text = if (result.missing.isEmpty()) "" else "Fill in ${result.missing.joinToString()} to open it."
        listOf(open, copyUri, copyAdb).forEach { it.isEnabled = result.missing.isEmpty() }
    }

    private fun refreshLayout() {
        revalidate()
        repaint()
    }

    private fun adbCommand(uri: String) =
        "adb shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${uri.replace("'", "'\\''")}' -p $packageName"

    private fun JBTextField.onChange(action: () -> Unit) = document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = action()
        override fun removeUpdate(e: DocumentEvent?) = action()
        override fun changedUpdate(e: DocumentEvent?) = action()
    })

    internal val previewForTest: String get() = preview.text
    internal val missingForTest: String get() = missing.text
    internal val openForTest: DesignButton get() = open
    internal fun fieldForTest(name: String): JBTextField = fields.getValue(name)
    internal fun addExtraForTest(key: String, value: String) = addExtra(key, value)
}
