package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.domain.deeplinks.UriQueryParameters
import io.github.dkej123.devicecockpit.intellij.ui.common.*
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Query parameters of design/DEEP_LINK_DIALOG.md §2a: dashed empty state, else a Key · Value grid. */
internal class QueryParameterEditor(private val onEdit: (List<Pair<String, String>>) -> Unit) : JPanel() {
    private val rows = mutableListOf<Pair<DeepLinkField, DeepLinkField>>()
    private val grid = JPanel(GridBagLayout()).apply { isOpaque = false }
    private val box = DeepLinkVisuals.surface(DeviceCockpitTheme.Colors.bg, DeviceCockpitTheme.Colors.border, 6).apply {
        name = "deviceDeepLinkQueryRows"
        layout = BorderLayout(); border = JBUI.Borders.empty(0, 6, 6, 6)
        add(grid, BorderLayout.CENTER)
    }
    private val hint = DeepLinkVisuals.label("Values are URL-encoded and kept in sync with the URI above.").apply {
        border = JBUI.Borders.emptyTop(5)
    }
    private val empty = DashedBox().apply {
        name = "deviceDeepLinkQueryEmpty"
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        border = JBUI.Borders.empty(8, 10)
        add(DeepLinkVisuals.label("No parameters. Add one, or type ", 11f))
        add(DeepLinkVisuals.label("?key=value", 11f, color = DeviceCockpitTheme.Colors.text).apply { font = DeepLinkVisuals.mono(11f) })
        add(DeepLinkVisuals.label(" in the URI.", 11f))
    }
    private val content = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(box, BorderLayout.NORTH)
        add(empty, BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)
    }
    private var restoring = false
    var onSizeChanged: () -> Unit = {}
    private val addButton = DeepLinkVisuals.button("+ Add parameter", DesignButtonStyle.LINK) { addParameter("", "", true) }.apply {
        name = "deviceDeepLinkAddParameter"
    }
    init {
        layout = BorderLayout()
        isOpaque = false
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(DeepLinkVisuals.caption("QUERY PARAMETERS"), BorderLayout.WEST)
            add(addButton, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        rebuild()
    }
    fun load(uri: String) {
        restoring = true
        try {
            rows.clear()
            UriQueryParameters.parse(uri).forEach { (key, value) -> addParameter(key, value, false) }
            rebuild()
        } finally { restoring = false }
    }
    internal fun values(): List<Pair<String, String>> = rows.map { it.first.text to it.second.text }
    internal fun addParameter(key: String = "", value: String = "", focus: Boolean = false) {
        val fields = DeepLinkField("key", 26) to DeepLinkField("value", 26)
        fields.first.font = DeepLinkVisuals.mono(11.5f, bold = true)
        fields.second.font = DeepLinkVisuals.mono(11.5f)
        fields.first.text = key; fields.second.text = value
        rows += fields
        listOf(fields.first, fields.second).forEach { field -> field.document.onQueryEdit { if (!restoring) onEdit(values()) } }
        rebuild()
        if (focus) fields.first.requestFocusInWindow()
    }
    private fun rebuild() {
        grid.removeAll()
        fun add(component: JComponent, x: Int, y: Int, weight: Double = 0.0, top: Int = 4) = grid.add(component, GridBagConstraints().apply {
            gridx = x; gridy = y; weightx = weight; fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(top, if (x == 0) 0 else 6, 0, 0)
        })
        fun fixed(component: JComponent, width: Int) = component.apply {
            preferredSize = Dimension(JBUI.scale(width), JBUI.scale(22)); minimumSize = preferredSize
        }
        // Grid of the prototype: key 1fr · '=' 10px · value 1.6fr · '×' 22px; header 5/4 px padding.
        add(DeepLinkVisuals.label("Key", 10.5f, true).apply { border = JBUI.Borders.emptyBottom(4) }, 0, 0, 1.0, 5)
        add(fixed(JLabel(), 10), 1, 0, top = 5)
        add(DeepLinkVisuals.label("Value", 10.5f, true).apply { border = JBUI.Borders.emptyBottom(4) }, 2, 0, 1.6, 5)
        add(fixed(JLabel(), 22), 3, 0, top = 5)
        rows.forEachIndexed { index, pair ->
            pair.first.name = "deviceDeepLinkQueryKey:$index"; pair.second.name = "deviceDeepLinkQueryValue:$index"
            add(pair.first, 0, index + 1, 1.0)
            add(fixed(DeepLinkVisuals.label("=", 11.5f).apply {
                font = DeepLinkVisuals.mono(11.5f); horizontalAlignment = SwingConstants.CENTER
            }, 10), 1, index + 1)
            add(pair.second, 2, index + 1, 1.6)
            add(fixed(DeepLinkVisuals.button("×", DesignButtonStyle.LINK) {
                rows.remove(pair); rebuild(); onEdit(values())
            }.apply {
                foreground = DeviceCockpitTheme.Colors.textDim; font = DeepLinkVisuals.ui(13f)
                toolTipText = "Remove parameter"; name = "deviceDeepLinkQueryRemove:$index"
            }, 22), 3, index + 1)
        }
        box.isVisible = rows.isNotEmpty(); hint.isVisible = rows.isNotEmpty(); empty.isVisible = rows.isEmpty()
        revalidate(); repaint(); onSizeChanged()
    }
}

/** 1 px dashed `border` outline, radius 6 — the empty state of the parameter list. */
private class DashedBox : JPanel() {
    init { isOpaque = false }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = DeviceCockpitTheme.Colors.border
            val dash = JBUIScale.scale(3f)
            g.stroke = BasicStroke(JBUIScale.scale(1f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(dash, dash), 0f)
            g.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
        } finally { g.dispose() }
    }
}

private fun javax.swing.text.Document.onQueryEdit(action: () -> Unit) = addDocumentListener(object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent?) = action()
    override fun removeUpdate(e: DocumentEvent?) = action()
    override fun changedUpdate(e: DocumentEvent?) = action()
})
