package dev.acme.adbtoolbox.intellij.inspector

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.layout.InspectorCapture
import dev.acme.adbtoolbox.application.layout.LayoutInspectorState
import dev.acme.adbtoolbox.domain.layout.AccessibilityIssue
import dev.acme.adbtoolbox.domain.layout.AccessibilityIssueKind
import dev.acme.adbtoolbox.domain.layout.AccessibilityStop
import dev.acme.adbtoolbox.domain.layout.LayoutMeasurement
import dev.acme.adbtoolbox.domain.layout.UiNode
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.TransferHandler
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.math.roundToInt

/**
 * The Layout Inspector editor tab (task 064, design §9–10): toolbar, hierarchy tree, canvas,
 * attributes / accessibility panel and status bar around one frozen capture.
 */
class LayoutInspectorPanel(
    private val onRecapture: () -> Unit,
    private val onOpenQuickToggles: () -> Unit,
    private val chooseOverlayFile: () -> File?,
) : JPanel(BorderLayout()) {

    // ---- canvas ----
    private val canvas = InspectorCanvas(
        onHover = { node -> hoveredChanged(node) },
        onSelect = { node -> select(node, fromTree = false) },
        onCursor = { x, y -> cursorLabel.text = if (x == null || y == null) "" else "x ${x.roundToInt()}  y ${y.roundToInt()} dp" },
        onPick = { color ->
            lastColor = color
            copy(InspectorCanvas.hex(color))
            message("Copied ${InspectorCanvas.hex(color)}")
        },
        onZoomChanged = { refreshZoomLabel() },
    )
    private val canvasScroll = JBScrollPane().apply {
        border = BorderFactory.createEmptyBorder()
        // Fit follows the viewport on every layout, not only after a resize event arrives.
        viewport = object : com.intellij.ui.components.JBViewport() {
            override fun doLayout() {
                if (canvas.fitSize != extentSize) {
                    canvas.fitSize = extentSize
                    refreshZoomLabel()
                }
                super.doLayout()
            }
        }
        setViewportView(canvas)
    }
    private var lastColor: Color? = null
    private var capture: InspectorCapture? = null

    // ---- toolbar ----
    private val recaptureButton = DesignButton("Re-capture", DesignButtonStyle.SECONDARY).apply { addActionListener { onRecapture() } }
    private val metaLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
        foreground = AdbToolboxTheme.Colors.textDim
        minimumSize = Dimension(0, preferredSize.height)
    }
    private val gridChip = chip("8 dp grid", "G") { canvas.grid = it }
    private val pickerChip = chip("Color picker", "I") { canvas.picker = it }
    private val overlayChip = chip("Overlay", null) { on -> if (on && canvas.overlay == null) chooseOverlay() else if (!on) clearOverlay() }
    private val auditChip = chip("Accessibility", "A") { setAudit(it) }
    private val treeChip = chip("Tree", null) { treePanel.isVisible = it; revalidate() }.apply { isVisible = false; isSelected = true }
    private val zoomOut = JButton("−").apply { addActionListener { canvas.zoomBy(-1) }; toolTipText = "Zoom out (−)" }
    private val zoomLabel = JButton("Fit").apply {
        isBorderPainted = false
        isContentAreaFilled = false
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
        toolTipText = "Fit (F) · 100 % (1)"
        addActionListener { canvas.zoom = null }
    }
    private val zoomIn = JButton("+").apply { addActionListener { canvas.zoomBy(1) }; toolTipText = "Zoom in (+)" }
    private val toolbar = run {
        val spacer = flexSpacer()
        flexRow(JBUI.scale(6), recaptureButton, metaLabel, spacer, treeChip, gridChip, pickerChip, overlayChip, auditChip, zoomOut, zoomLabel, zoomIn, fill = metaLabel)
    }.apply {
        background = AdbToolboxTheme.Colors.header
        isOpaque = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(0, 8),
        )
        preferredSize = Dimension(0, JBUI.scale(34))
    }

    // ---- overlay bar ----
    private val overlayName = JBLabel("").apply { font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f)) }
    private val scaleButtons = OverlayScale.entries.associateWith { s -> JToggleButton(s.label).apply { addActionListener { updateOverlay { it.copy(scale = s) } } } }
    private val opacity = JSlider(0, 100, 50).apply {
        preferredSize = Dimension(JBUI.scale(110), preferredSize.height)
        addChangeListener { updateOverlay { it.copy(opacity = value) } }
    }
    private val blend = JToggleButton("Difference").apply { addActionListener { updateOverlay { it.copy(difference = isSelected) } } }
    private val nudgeLabel = JBLabel("").apply { font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f)) }
    private val overlayBar = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(4))).apply {
        background = AdbToolboxTheme.Colors.panel
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border)
        add(overlayName)
        scaleButtons.values.forEach(::add)
        add(JBLabel("Opacity"))
        add(opacity)
        add(blend)
        add(nudgeLabel)
        add(JButton("Reset").apply { addActionListener { updateOverlay { it.copy(nudgeX = 0, nudgeY = 0) } } })
        add(JButton("Clear").apply { addActionListener { clearOverlay() } })
        isVisible = false
    }

    // ---- banners and states ----
    private val banner = JBLabel("").apply {
        isOpaque = true
        border = JBUI.Borders.empty(6, 10)
        isVisible = false
    }
    private val stateTitle = JBLabel("").apply { font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(13f)); alignmentX = Component.CENTER_ALIGNMENT }
    private val stateDetail = JBLabel("").apply { foreground = AdbToolboxTheme.Colors.textDim; alignmentX = Component.CENTER_ALIGNMENT }
    private val stateMono = JBLabel("").apply { font = AdbToolboxTheme.Typography.mono; foreground = AdbToolboxTheme.Colors.red; alignmentX = Component.CENTER_ALIGNMENT }
    private val retryButton = DesignButton("Retry", DesignButtonStyle.PRIMARY).apply { addActionListener { onRecapture() } }
    private val quickTogglesButton = DesignButton("Open Quick toggles", DesignButtonStyle.SECONDARY).apply { addActionListener { onOpenQuickToggles() } }
    private val statePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.bg
        border = JBUI.Borders.empty(80, 24)
        add(stateTitle)
        add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        add(stateMono)
        add(stateDetail)
        add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
        add(JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(retryButton)
            add(quickTogglesButton)
        })
    }
    private val centerCards = JPanel(CardLayout()).apply {
        add(canvasScroll, CANVAS)
        add(statePanel, STATE)
    }

    // ---- tree ----
    private val treeSearch = JBTextField().apply {
        emptyText.text = "Search text or resource-id…"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildTree()
            override fun removeUpdate(e: DocumentEvent) = rebuildTree()
            override fun changedUpdate(e: DocumentEvent) = rebuildTree()
        })
    }
    private val tree = object : Tree(DefaultTreeModel(DefaultMutableTreeNode())) {
        // Always the platform tree UI (what the IDE installs anyway), whose indents come from the painter below.
        override fun updateUI() = setUI(com.intellij.ui.tree.ui.DefaultTreeUI())
    }.apply {
        // 12px per level (design §9), so a deep Settings hierarchy still fits 260px.
        putClientProperty(com.intellij.ui.tree.ui.Control.Painter.KEY, InspectorTreePainter)
        isRootVisible = true
        background = AdbToolboxTheme.Colors.panel
        cellRenderer = NodeRenderer()
        // Selecting from the canvas expands ancestors; scrolling to the whole row would pan sideways.
        scrollsOnExpand = false
        addTreeSelectionListener {
            val node = (lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? UiNode
            if (!syncing && node != null) select(node, fromTree = true)
        }
    }
    private val treeFooter = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
        foreground = AdbToolboxTheme.Colors.textFaint
        border = JBUI.Borders.empty(4, 8)
    }
    private val treeEmpty = JBLabel("").apply { foreground = AdbToolboxTheme.Colors.textDim; border = JBUI.Borders.empty(8); isVisible = false }
    private val treePanel = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6); add(treeSearch) }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            add(treeEmpty, BorderLayout.NORTH)
            add(JBScrollPane(tree).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(treeFooter, BorderLayout.SOUTH)
    }
    private var syncing = false
    private var matches: Set<Int> = emptySet()

    // ---- attributes ----
    private val attributes = AttributesPanel()

    // ---- audit ----
    private val auditPanel = AuditPanel()
    private val rightCards = JPanel(CardLayout()).apply {
        add(JBScrollPane(dev.acme.adbtoolbox.intellij.ui.common.ViewportWidthPanel().apply {
            layout = BorderLayout()
            add(attributes, BorderLayout.NORTH)
        }).apply {
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, ATTRIBUTES)
        add(auditPanel, AUDIT)
        // Its rows ellipsise or wrap, so the splitter may give it the design's 260–300px.
        minimumSize = Dimension(JBUI.scale(200), 0)
    }

    // ---- status bar ----
    private val cursorLabel = JBLabel("").apply { font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f)) }
    private val messageLabel = JBLabel("").apply { foreground = AdbToolboxTheme.Colors.textDim; minimumSize = Dimension(0, 0) }
    private val densityLabel = JBLabel("").apply { font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f)); foreground = AdbToolboxTheme.Colors.textFaint }
    private val statusBar = flexRow(JBUI.scale(10), cursorLabel, messageLabel, densityLabel, fill = messageLabel).apply {
        background = AdbToolboxTheme.Colors.header
        isOpaque = true
        border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AdbToolboxTheme.Colors.border), JBUI.Borders.empty(0, 8))
        preferredSize = Dimension(0, JBUI.scale(22))
    }

    private val mainSplit = JBSplitter(false, 0.75f).apply {
        firstComponent = JPanel(BorderLayout()).apply {
            add(overlayBar, BorderLayout.NORTH)
            add(centerCards, BorderLayout.CENTER)
        }
        secondComponent = rightCards
    }
    private val outerSplit = JBSplitter(false, 0.2f).apply {
        firstComponent = treePanel
        secondComponent = mainSplit
    }

    init {
        background = AdbToolboxTheme.Colors.bg
        add(JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(banner, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(outerSplit, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
        installKeys()
        installDrop()
        update(LayoutInspectorState())
    }

    override fun doLayout() {
        responsive()
        super.doLayout()
    }

    // ---- rendering ----

    fun update(state: LayoutInspectorState) {
        val c = state.capture
        val newCapture = c != null && c !== capture
        capture = c
        recaptureButton.isEnabled = state.deviceOnline && !state.capturing
        recaptureButton.text = if (state.capturing) "Capturing…" else "Re-capture"
        recaptureButton.toolTipText = if (!state.deviceOnline) "Connect a device to use this" else null
        if (newCapture && c != null) {
            canvas.screenshot = runCatching { ImageIO.read(ByteArrayInputStream(c.snapshot.png)) }.getOrNull()
            canvas.hierarchy = c.snapshot.hierarchy
            canvas.audit = c.audit
            canvas.selected = null
            canvas.hovered = null
            attributes.show(null, c)
            rebuildTree()
            auditPanel.show(c)
            auditChip.text = "Accessibility" + c.audit.stops.count { it.issues.isNotEmpty() }.let { if (it > 0) "  $it" else "" }
            val shot = canvas.screenshot
            val h = c.snapshot.hierarchy
            metaLabel.text = listOfNotNull(
                c.deviceName,
                SimpleDateFormat("HH:mm:ss").format(Date(c.capturedAtMillis)),
                c.activity,
                shot?.let { "${it.width}×${it.height}" },
                "${h.densityDpi} dpi",
            ).joinToString(" · ")
            densityLabel.text = "1 dp = ${"%.3f".format(h.densityDpi / 160.0).trimEnd('0').trimEnd('.')} px · ${h.densityDpi} dpi"
        }
        // States (design §9 pills).
        val cards = centerCards.layout as CardLayout
        val failure = state.error
        when {
            state.capturing && c == null -> {
                cards.show(centerCards, STATE)
                stateTitle.text = "Capturing ${state.deviceName ?: "the device"}…"
                stateMono.text = ""
                stateDetail.text = "Screenshot and uiautomator hierarchy. Usually under 2 s; screens that never go idle take longer."
                retryButton.isVisible = false
                quickTogglesButton.isVisible = false
            }
            failure != null && c == null -> showError(failure)
            c == null -> {
                cards.show(centerCards, STATE)
                stateTitle.text = if (state.deviceOnline) "No capture yet" else "No device connected"
                stateMono.text = ""
                stateDetail.text = if (state.deviceOnline) "Re-capture takes a screenshot and the hierarchy." else "Connect a device to capture its layout."
                retryButton.isVisible = false
                quickTogglesButton.isVisible = false
            }
            else -> cards.show(centerCards, CANVAS)
        }
        val error = state.error
        when {
            error != null && c != null -> banner(
                "Couldn’t capture the layout — $error. Usually an animation that never settles: turn Animations off in Device → Quick toggles, then retry.",
                AdbToolboxTheme.Colors.redBg,
            )
            state.deviceGone && c != null -> banner(
                "${c.deviceName} disconnected — this capture stays readable. Re-capture needs an online device.",
                AdbToolboxTheme.Colors.amberBg,
            )
            canvas.secure && c != null -> banner(
                "${c.activity?.substringBefore('/') ?: "This app"} marks this screen secure, so the screenshot is black. " +
                    "The hierarchy is still inspectable as wireframes; colors and the overlay need a screenshot.",
                AdbToolboxTheme.Colors.amberBg,
            )
            state.capturing -> banner("Capturing ${state.deviceName ?: "the device"}…", AdbToolboxTheme.Colors.brandBg)
            else -> banner.isVisible = false
        }
        overlayChip.isEnabled = !canvas.secure
        refreshZoomLabel()
        revalidate()
        repaint()
    }

    private fun showError(error: String) {
        (centerCards.layout as CardLayout).show(centerCards, STATE)
        stateTitle.text = "Couldn’t capture the layout"
        stateMono.text = error
        stateDetail.text = "Usually an animation that never settles. Turn Animations off in Device → Quick toggles, then retry."
        retryButton.isVisible = true
        quickTogglesButton.isVisible = true
    }

    private fun banner(text: String, background: Color) {
        banner.text = "<html>$text</html>"
        banner.background = background
        banner.isVisible = true
    }

    private fun refreshZoomLabel() {
        val percent = (canvas.effectiveZoom * 100).roundToInt()
        zoomLabel.text = if (canvas.zoom == null) "Fit · $percent%" else "$percent%"
    }

    private fun responsive() {
        val narrow = width in 1..999
        treeChip.isVisible = narrow
        if (!narrow) treePanel.isVisible = true else treePanel.isVisible = treeChip.isSelected
        val treeWidth = when {
            !treePanel.isVisible -> 0
            width >= 1300 -> JBUI.scale(260)
            width >= 1000 -> JBUI.scale(220)
            else -> width / 4
        }
        // A splitter's proportion shares its width minus the divider.
        val outerFree = width - outerSplit.dividerWidth
        if (outerFree > treeWidth) outerSplit.proportion = treeWidth.toFloat() / outerFree
        // Right panel 300 / 280 / 260 (design §9).
        val rightWidth = JBUI.scale(
            when {
                width >= 1300 -> 300
                width >= 1000 -> 280
                else -> 260
            },
        )
        val mainFree = width - treeWidth - outerSplit.dividerWidth - mainSplit.dividerWidth
        if (mainFree > rightWidth) mainSplit.proportion = 1f - rightWidth.toFloat() / mainFree
    }

    // ---- selection ----

    private fun hoveredChanged(node: UiNode?) {
        val c = capture ?: return
        val sel = canvas.selected
        message(
            when {
                node == null -> ""
                sel != null && node != sel -> {
                    val lines = LayoutMeasurement.between(sel.bounds, node.bounds, c.snapshot.hierarchy)
                    "${sel.shortClassName} → ${node.shortClassName} · " + lines.joinToString(" · ") { "${it.dp} dp" }
                }
                else -> "${node.shortClassName} · ${node.label}"
            },
        )
        if (auditChip.isSelected) auditPanel.highlight(node)
    }

    private fun select(node: UiNode?, fromTree: Boolean) {
        val c = capture ?: return
        canvas.selected = node
        attributes.show(node, c)
        if (!fromTree && node != null) selectInTree(node)
        if (auditChip.isSelected) auditPanel.highlight(node)
    }

    private fun selectInTree(node: UiNode) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val match = root.depthFirstEnumeration().toList().firstOrNull { ((it as DefaultMutableTreeNode).userObject as? UiNode)?.id == node.id } as? DefaultMutableTreeNode
            ?: return
        syncing = true
        val path = TreePath(match.path)
        tree.selectionPath = path
        tree.getPathBounds(path)?.let { bounds -> tree.scrollRectToVisible(java.awt.Rectangle(0, bounds.y, 1, bounds.height)) }
        syncing = false
    }

    private fun rebuildTree() {
        val c = capture ?: return
        val query = treeSearch.text.trim()
        fun matchesQuery(n: UiNode) = query.isEmpty() || listOf(n.text, n.contentDescription, n.resourceId, n.className).any { it.contains(query, ignoreCase = true) }
        matches = if (query.isEmpty()) emptySet() else c.snapshot.hierarchy.root.descendantsAndSelf().filter(::matchesQuery).map { it.id }.toSet()
        fun build(n: UiNode): DefaultMutableTreeNode? {
            val children = n.children.mapNotNull(::build)
            if (query.isNotEmpty() && n.id !in matches && children.isEmpty()) return null
            return DefaultMutableTreeNode(n).apply { children.forEach(::add) }
        }
        val root = build(c.snapshot.hierarchy.root) ?: DefaultMutableTreeNode()
        tree.model = DefaultTreeModel(root)
        for (i in 0 until 400) {
            if (i >= tree.rowCount) break
            tree.expandRow(i)
        }
        val total = c.snapshot.hierarchy.root.descendantsAndSelf().count()
        treeFooter.text = "$total nodes · uiautomator"
        treeEmpty.isVisible = query.isNotEmpty() && matches.isEmpty()
        treeEmpty.text = "<html>Nothing matches “$query”. Search covers text, content-description, resource-id and class.</html>"
    }

    // ---- modes ----

    private fun setAudit(on: Boolean) {
        canvas.auditMode = on
        (rightCards.layout as CardLayout).show(rightCards, if (on) AUDIT else ATTRIBUTES)
    }

    private fun chooseOverlay() {
        val file = chooseOverlayFile()
        if (file == null) {
            overlayChip.isSelected = false
            return
        }
        loadOverlay(file)
    }

    fun loadOverlay(file: File) {
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: run {
            message("${file.name} is not a PNG")
            overlayChip.isSelected = false
            return
        }
        val c = capture
        val screenDp = c?.let { it.snapshot.hierarchy.dp(it.snapshot.hierarchy.root.bounds.width) } ?: 411.0
        val scale = OverlayScale.entries.filter { it.factor != null }.minByOrNull { kotlin.math.abs(image.width / it.factor!! - screenDp) } ?: OverlayScale.X1
        canvas.overlay = DesignOverlay(file.name, image, scale)
        overlayChip.isSelected = true
        refreshOverlayBar()
    }

    private fun updateOverlay(change: (DesignOverlay) -> DesignOverlay) {
        val current = canvas.overlay ?: return
        canvas.overlay = change(current)
        refreshOverlayBar()
    }

    private fun clearOverlay() {
        canvas.overlay = null
        overlayChip.isSelected = false
        refreshOverlayBar()
    }

    private fun refreshOverlayBar() {
        val o = canvas.overlay
        overlayBar.isVisible = o != null
        if (o != null) {
            overlayName.text = o.name
            scaleButtons.forEach { (s, b) -> b.isSelected = s == o.scale }
            blend.isSelected = o.difference
            nudgeLabel.text = "x ${if (o.nudgeX >= 0) "+" else ""}${o.nudgeX}  y ${if (o.nudgeY >= 0) "+" else ""}${o.nudgeY} dp"
        }
        revalidate()
    }

    private fun installDrop() {
        canvas.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && !canvas.secure

            override fun importData(support: TransferSupport): Boolean {
                @Suppress("UNCHECKED_CAST")
                val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: return false
                files.firstOrNull { it.name.endsWith(".png", ignoreCase = true) }?.let(::loadOverlay) ?: return false
                return true
            }
        }
    }

    // ---- keys (canvas focused, design §9) ----

    private fun installKeys() {
        fun bind(key: String, stroke: KeyStroke, action: () -> Unit) {
            canvas.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, key)
            canvas.actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = action()
            })
        }
        bind("grid", KeyStroke.getKeyStroke('g')) { gridChip.doClick() }
        bind("picker", KeyStroke.getKeyStroke('i')) { pickerChip.doClick() }
        bind("audit", KeyStroke.getKeyStroke('a')) { auditChip.doClick() }
        bind("fit", KeyStroke.getKeyStroke('f')) { canvas.zoom = null }
        bind("actual", KeyStroke.getKeyStroke('1')) { canvas.zoom = 1.0 }
        bind("zoomIn", KeyStroke.getKeyStroke('+')) { canvas.zoomBy(1) }
        bind("zoomIn2", KeyStroke.getKeyStroke('=')) { canvas.zoomBy(1) }
        bind("zoomOut", KeyStroke.getKeyStroke('-')) { canvas.zoomBy(-1) }
        bind("escape", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)) {
            if (canvas.picker) pickerChip.doClick() else select(null, fromTree = false)
        }
        val copyMask = if (com.intellij.openapi.util.SystemInfo.isMac) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
        bind("copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, copyMask)) {
            val text = if (canvas.picker) lastColor?.let(InspectorCanvas::hex) else canvas.selected?.let { it.resourceId.ifEmpty { it.text } }
            text?.takeIf { it.isNotEmpty() }?.let {
                copy(it)
                message("Copied $it")
            }
        }
        listOf(KeyEvent.VK_UP to (0 to -1), KeyEvent.VK_DOWN to (0 to 1), KeyEvent.VK_LEFT to (-1 to 0), KeyEvent.VK_RIGHT to (1 to 0)).forEach { (key, delta) ->
            bind("arrow$key", KeyStroke.getKeyStroke(key, 0)) { arrow(key, delta, 1) }
            bind("arrowShift$key", KeyStroke.getKeyStroke(key, KeyEvent.SHIFT_DOWN_MASK)) { arrow(key, delta, 8) }
        }
    }

    /** With an overlay loaded the arrows nudge it; otherwise ↑ parent, ↓ first child, ← → siblings. */
    private fun arrow(key: Int, delta: Pair<Int, Int>, step: Int) {
        if (canvas.overlay != null) {
            updateOverlay { it.copy(nudgeX = it.nudgeX + delta.first * step, nudgeY = it.nudgeY + delta.second * step) }
            return
        }
        val c = capture ?: return
        val sel = canvas.selected ?: return select(c.snapshot.hierarchy.root, fromTree = false)
        val parent = c.snapshot.hierarchy.root.descendantsAndSelf().firstOrNull { p -> p.children.any { it.id == sel.id } }
        val next = when (key) {
            KeyEvent.VK_UP -> parent
            KeyEvent.VK_DOWN -> sel.children.firstOrNull()
            else -> parent?.children?.let { siblings ->
                val i = siblings.indexOfFirst { it.id == sel.id }
                siblings.getOrNull(i + if (key == KeyEvent.VK_LEFT) -1 else 1)
            }
        }
        next?.let { select(it, fromTree = false) }
    }

    private fun message(text: String) {
        messageLabel.text = text
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    /** Toolbar toggle chip (design §9): on = `accentBg` + `accentBorder`, off = `panel` + `border`, in either theme. */
    private fun chip(label: String, key: String?, onToggle: (Boolean) -> Unit) = object : JToggleButton(label) {
        // 22px tall at whatever width the current text needs (the audit chip's count changes it).
        override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, JBUI.scale(22))

        override fun paintComponent(graphics: java.awt.Graphics) {
            val g = graphics.create() as java.awt.Graphics2D
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = if (isSelected) AdbToolboxTheme.Colors.accentBg else AdbToolboxTheme.Colors.panel
                val arc = AdbToolboxTheme.Radii.field * 2
                g.fillRoundRect(0, 0, width, height, arc, arc)
            } finally {
                g.dispose()
            }
            super.paintComponent(graphics)
        }
    }.apply {
        font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(11f))
        foreground = AdbToolboxTheme.Colors.text
        isOpaque = false
        isContentAreaFilled = false
        isFocusPainted = false
        fun refresh() {
            border = BorderFactory.createCompoundBorder(
                dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder(
                    if (isSelected) AdbToolboxTheme.Colors.accentBorder else AdbToolboxTheme.Colors.border,
                    radius = { AdbToolboxTheme.Radii.field },
                ),
                JBUI.Borders.empty(0, 8),
            )
        }
        refresh()
        addItemListener { refresh() }
        toolTipText = key?.let { "$label ($it)" } ?: label
        addActionListener { onToggle(isSelected) }
    }

    // ---- test seams ----
    internal val canvasForTest: InspectorCanvas get() = canvas
    internal val metaForTest: String get() = metaLabel.text
    internal val bannerForTest: String? get() = banner.text.takeIf { banner.isVisible }
    internal val stateTitleForTest: String get() = stateTitle.text
    internal val auditChipForTest: JToggleButton get() = auditChip
    internal val gridChipForTest: JToggleButton get() = gridChip
    internal val attributesForTest: AttributesPanel get() = attributes
    internal val auditPanelForTest: AuditPanel get() = auditPanel
    internal val treeForTest: Tree get() = tree
    internal val treeSearchForTest: JBTextField get() = treeSearch
    internal val treeFooterForTest: String get() = treeFooter.text
    internal val messageForTest: String get() = messageLabel.text
    internal val zoomTextForTest: String get() = zoomLabel.text
    internal val treePanelForTest: JComponent get() = treePanel
    internal val rightPanelForTest: JComponent get() = rightCards
    internal fun selectForTest(node: UiNode) = select(node, fromTree = false)
    internal fun hoverForTest(node: UiNode) = hoveredChanged(node)

    private inner class NodeRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(tree: javax.swing.JTree, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
            // Theme colors rather than the LaF's tree defaults, which stay light in a dark editor.
            backgroundNonSelectionColor = AdbToolboxTheme.Colors.panel
            backgroundSelectionColor = AdbToolboxTheme.Colors.accentBg
            borderSelectionColor = null
            textNonSelectionColor = AdbToolboxTheme.Colors.text
            textSelectionColor = AdbToolboxTheme.Colors.text
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = (value as? DefaultMutableTreeNode)?.userObject as? UiNode
            icon = null
            if (node != null) {
                val detail = when {
                    node.text.isNotEmpty() -> "“${node.text.take(40)}”"
                    node.contentDescription.isNotEmpty() -> "“${node.contentDescription.take(40)}”"
                    node.resourceId.isNotEmpty() -> node.shortResourceId
                    else -> ""
                }
                val problem = capture?.audit?.stops?.any { it.node.id == node.id && it.issues.isNotEmpty() } == true
                val hit = node.id in matches
                text = "<html>${if (problem) "<font color='#e0656b'>●</font> " else ""}${if (hit) "<b>" else ""}${node.shortClassName.ifEmpty { "View" }}${if (hit) "</b>" else ""} " +
                    "<font color='#8a8d93'><tt>${escape(detail)}</tt></font></html>"
            }
            return this
        }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
    }

    /** Attributes of the selected element (design §9): key/value rows with Copy, then the state chips. */
    inner class AttributesPanel : JPanel() {
        internal var rowsForTest: Map<String, String> = emptyMap()
            private set

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        fun show(node: UiNode?, c: InspectorCapture) {
            removeAll()
            if (node == null) {
                add(dev.acme.adbtoolbox.intellij.ui.common.WrappingText(
                    "Click an element on the screenshot or in the tree to see its attributes.",
                    AdbToolboxTheme.Typography.body,
                    AdbToolboxTheme.Colors.textDim,
                ).apply { alignmentX = LEFT_ALIGNMENT })
                rowsForTest = emptyMap()
            } else {
                val h = c.snapshot.hierarchy
                val b = node.bounds
                add(JBLabel(node.shortClassName.ifEmpty { "View" }).apply { font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(12.5f)); alignmentX = LEFT_ALIGNMENT })
                add(JBLabel(node.className).apply { font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f)); foreground = AdbToolboxTheme.Colors.textDim; alignmentX = LEFT_ALIGNMENT })
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
                val rows = linkedMapOf(
                    "resource-id" to node.resourceId,
                    "text" to node.text,
                    "content-description" to node.contentDescription,
                    "class" to node.className,
                    "package" to node.packageName,
                    "bounds (dp)" to "x ${h.dp(b.left).roundToInt()}  y ${h.dp(b.top).roundToInt()}  ·  ${h.dp(b.width).roundToInt()} × ${h.dp(b.height).roundToInt()}",
                    "bounds (px)" to "[${b.left},${b.top}][${b.right},${b.bottom}]",
                    "text size" to (LayoutMeasurement.estimatedTextSp(node, h)?.let { "≈ $it sp (estimated from glyph height)" } ?: ""),
                    "background" to (canvas.sampleCenter(node)?.let(InspectorCanvas::hex) ?: ""),
                )
                rowsForTest = rows
                rows.forEach { (key, value) -> add(row(key, value)) }
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
                add(JBLabel("STATE").apply { font = AdbToolboxTheme.Typography.groupLabel; foreground = AdbToolboxTheme.Colors.textFaint; alignmentX = LEFT_ALIGNMENT })
                val f = node.flags
                add(JPanel(dev.acme.adbtoolbox.intellij.ui.common.WrappingFlowLayout(FlowLayout.LEADING, JBUI.scale(4), JBUI.scale(4))).apply {
                    alignmentX = LEFT_ALIGNMENT
                    isOpaque = false
                    listOf(
                        "clickable" to f.clickable, "long-clickable" to f.longClickable, "focusable" to f.focusable,
                        "enabled" to f.enabled, "checkable" to f.checkable, "checked" to f.checked, "selected" to f.selected,
                        "scrollable" to f.scrollable, "password" to f.password,
                    ).forEach { (name, on) ->
                        add(JBLabel(if (on) name else "<html><s>$name</s></html>").apply {
                            font = AdbToolboxTheme.Typography.mono.deriveFont(if (on) Font.BOLD else Font.PLAIN, JBUI.scale(10f))
                            foreground = if (on) AdbToolboxTheme.Colors.text else AdbToolboxTheme.Colors.textFaint
                            border = BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(if (on) AdbToolboxTheme.Colors.borderStrong else AdbToolboxTheme.Colors.border),
                                JBUI.Borders.empty(1, 4),
                            )
                        })
                    }
                })
                add(dev.acme.adbtoolbox.intellij.ui.common.WrappingText(
                    "With an element selected, hover another to measure the distance between them. ↑ parent · ↓ first child · ← → siblings.",
                    AdbToolboxTheme.Typography.caption,
                    AdbToolboxTheme.Colors.textFaint,
                ).apply { alignmentX = LEFT_ALIGNMENT })
            }
            revalidate()
            repaint()
        }

        private fun row(key: String, value: String) = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(38))
            add(JBLabel(key.uppercase()).apply { font = AdbToolboxTheme.Typography.groupLabel; foreground = AdbToolboxTheme.Colors.textFaint }, BorderLayout.NORTH)
            add(JBLabel(value.ifEmpty { "—" }).apply {
                font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(11f))
                toolTipText = value.takeIf { it.isNotEmpty() }
                minimumSize = Dimension(0, preferredSize.height)
            }, BorderLayout.CENTER)
            if (value.isNotEmpty()) {
                add(DesignButton("Copy", DesignButtonStyle.LINK).apply { addActionListener { copy(value); message("Copied $key") } }, BorderLayout.EAST)
            }
        }
    }

    /** Accessibility mode (design §10): summary cards, problems-only filter, Copy report, the stop list. */
    inner class AuditPanel : JPanel(BorderLayout()) {
        private val meta = JBLabel("").apply { font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f)); foreground = AdbToolboxTheme.Colors.textFaint }
        private val cards = JPanel(GridLayout(1, 3, JBUI.scale(6), 0)).apply { isOpaque = false }
        private val problemsOnly = JBCheckBox("Problems only").apply {
            isOpaque = false
            foreground = AdbToolboxTheme.Colors.text
            addActionListener { refill() }
        }
        private val model = DefaultListModel<AccessibilityStop>()
        private val list = object : JBList<AccessibilityStop>(model) {
            // Rows wrap to the panel instead of widening the list past it.
            override fun getScrollableTracksViewportWidth() = true

            override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
                val resized = width != this.width
                super.setBounds(x, y, width, height)
                // Wrapped rows change height with the width; drop the cached heights.
                if (resized) {
                    fixedCellHeight = 1
                    fixedCellHeight = -1
                }
            }
        }.apply {
            cellRenderer = StopRenderer()
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting && !syncing) selectedValue?.let { select(it.node, fromTree = false) }
            }
        }
        private var current: InspectorCapture? = null

        init {
            border = JBUI.Borders.empty(10)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(flexRow(JBUI.scale(8), JBLabel("Accessibility").apply { font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(12.5f)) }, flexSpacer(), meta).apply { alignmentX = LEFT_ALIGNMENT })
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
                add(cards.apply { alignmentX = LEFT_ALIGNMENT })
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
                add(flexRow(JBUI.scale(8), problemsOnly, flexSpacer(), DesignButton("Copy report", DesignButtonStyle.SECONDARY).apply { addActionListener { copyReport() } }).apply { alignmentX = LEFT_ALIGNMENT })
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
                add(dev.acme.adbtoolbox.intellij.ui.common.WrappingText(
                    "Order is the estimated TalkBack linear order: focusable and text elements by position.",
                    AdbToolboxTheme.Typography.caption,
                    AdbToolboxTheme.Colors.textFaint,
                ))
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            }, BorderLayout.NORTH)
            add(JBScrollPane(list).apply {
                border = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }

        fun show(c: InspectorCapture) {
            current = c
            val stops = c.audit.stops
            meta.text = "${stops.size} stops · ${stops.count { it.issues.isNotEmpty() }} with problems"
            fun count(kind: AccessibilityIssueKind) = stops.count { s -> s.issues.any { it.kind == kind } }
            cards.removeAll()
            cards.add(card("Unlabeled buttons", count(AccessibilityIssueKind.NoLabel) + count(AccessibilityIssueKind.NotAccessibilityFriendly)))
            cards.add(card("Images without description", c.audit.unannouncedImages.size))
            cards.add(card("Targets < 48 dp", count(AccessibilityIssueKind.SmallTouchTarget)))
            refill()
        }

        fun highlight(node: UiNode?) {
            val index = (0 until model.size()).firstOrNull { model[it].node.id == node?.id } ?: return list.clearSelection()
            syncing = true
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
            syncing = false
        }

        private fun refill() {
            model.clear()
            current?.audit?.stops?.filter { !problemsOnly.isSelected || it.issues.isNotEmpty() }?.forEach(model::addElement)
        }

        internal fun reportForTest(): String = report()

        internal val cardTextsForTest: List<String> get() = cards.components.map { (it as JComponent).getClientProperty("text") as String }

        private fun report(): String {
            val c = current ?: return ""
            val stops = c.audit.stops
            return buildString {
                append("# Accessibility audit — ${c.activity ?: c.deviceName}\n\n")
                append("${c.deviceName} · ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(c.capturedAtMillis))} · ")
                append("${stops.size} stops, ${stops.count { it.issues.isNotEmpty() }} with problems\n\n")
                append(c.audit.markdown().substringAfter("\n\n").substringAfter("\n\n"))
            }
        }

        private fun copyReport() {
            val c = current ?: return
            copy(report())
            message("Copied audit report (Markdown) · ${c.audit.stops.size} stops, ${c.audit.stops.count { it.issues.isNotEmpty() }} with problems")
        }

        private fun card(title: String, count: Int): JComponent = RoundedSurface(
            if (count > 0) AdbToolboxTheme.Colors.redBg else AdbToolboxTheme.Colors.panel,
            if (count > 0) AdbToolboxTheme.Colors.redBorder else AdbToolboxTheme.Colors.border,
        ).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(6, 8)
            putClientProperty("text", "$title $count")
            add(JBLabel(count.toString()).apply {
                font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD, JBUI.scale(16f))
                foreground = if (count > 0) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.text
            }, BorderLayout.NORTH)
            // Three cards share 260–300px, so titles wrap at the card's width.
            add(JBLabel("<html><body style='width: ${JBUI.scale(60)}px'>${title.replace("<", "&lt;")}</body></html>").apply { font = AdbToolboxTheme.Typography.caption; foreground = AdbToolboxTheme.Colors.textDim }, BorderLayout.CENTER)
        }

        /**
         * A row wraps its announcement and problems to the list's width (design §10 shows them in
         * full). Rows are built fresh — a reused text area keeps its last wrapped height — and laid
         * out here, since the renderer pane can only validate them once the list has a peer.
         */
        private inner class StopRenderer : ListCellRenderer<AccessibilityStop> {
            override fun getListCellRendererComponent(list: javax.swing.JList<out AccessibilityStop>, value: AccessibilityStop, index: Int, selected: Boolean, focus: Boolean): Component {
                val number = JBLabel(value.order.toString()).apply {
                    font = AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD)
                    foreground = if (value.issues.isNotEmpty()) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.accent
                    verticalAlignment = javax.swing.SwingConstants.TOP
                    preferredSize = Dimension(JBUI.scale(22), preferredSize.height)
                }
                // The list runs under the scroll bar, so the row's right inset leaves its width free.
                val insets = JBUI.insets(4, 6, 4, 16)
                val textWidth = (list.width - insets.left - insets.right - number.preferredSize.width).coerceAtLeast(JBUI.scale(80))
                fun wrapping(text: String, font: Font, color: Color) =
                    dev.acme.adbtoolbox.intellij.ui.common.WrappingText(text, font, color).apply { setSize(textWidth, Short.MAX_VALUE.toInt()) }
                val body = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(wrapping("“${value.spoken}”", AdbToolboxTheme.Typography.body.deriveFont(Font.BOLD), AdbToolboxTheme.Colors.text))
                    add(JBLabel(value.node.shortClassName + (value.node.shortResourceId.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")).apply {
                        font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
                        foreground = AdbToolboxTheme.Colors.textDim
                        alignmentX = LEFT_ALIGNMENT
                    })
                    if (value.issues.isNotEmpty()) {
                        add(wrapping(value.issues.joinToString("  ·  ", transform = ::problemText), AdbToolboxTheme.Typography.caption.deriveFont(Font.BOLD), AdbToolboxTheme.Colors.red))
                    }
                }
                return JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(insets)
                    isOpaque = selected
                    background = AdbToolboxTheme.Colors.accentBg
                    add(number, BorderLayout.WEST)
                    add(body, BorderLayout.CENTER)
                    setSize(list.width, preferredSize.height)
                    doLayout()
                    body.doLayout()
                }
            }

            private fun problemText(issue: AccessibilityIssue) = when (issue.kind) {
                AccessibilityIssueKind.NoLabel -> "Unlabeled button"
                AccessibilityIssueKind.NotAccessibilityFriendly -> "Not accessibility friendly"
                AccessibilityIssueKind.SmallTouchTarget -> issue.message.substringAfter("Touch target ").substringBefore(",").let { "Target $it < 48" }
            }
        }
    }

    /** Hierarchy rows (design §9): 12px per level, the caret then the label, no guide lines. */
    private object InspectorTreePainter : com.intellij.ui.tree.ui.Control.Painter {
        private val step get() = JBUI.scale(12)

        override fun getControlOffset(control: com.intellij.ui.tree.ui.Control, depth: Int, leaf: Boolean): Int =
            if (depth <= 0 || leaf) -1 else (depth - 1) * step

        override fun getRendererOffset(control: com.intellij.ui.tree.ui.Control, depth: Int, leaf: Boolean): Int = when {
            depth < 0 -> -1
            depth == 0 -> 0
            else -> (depth - 1) * step + control.width + JBUI.scale(2)
        }

        override fun paint(
            c: Component, g: java.awt.Graphics, x: Int, y: Int, width: Int, height: Int,
            control: com.intellij.ui.tree.ui.Control, depth: Int, leaf: Boolean, expanded: Boolean, selected: Boolean,
        ) {
            val offset = getControlOffset(control, depth, leaf)
            if (offset >= 0) control.paint(c, g, x + offset, y, control.width, height, expanded, selected)
        }
    }

    private companion object {
        const val CANVAS = "canvas"
        const val STATE = "state"
        const val ATTRIBUTES = "attributes"
        const val AUDIT = "audit"
    }
}
