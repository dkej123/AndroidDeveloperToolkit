package io.github.dkej123.devicecockpit.intellij.deeplinks

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.dkej123.devicecockpit.application.deeplinks.*
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkCommands
import io.github.dkej123.devicecockpit.domain.packages.PackageEntry
import io.github.dkej123.devicecockpit.intellij.ui.common.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.*
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** The form and bounded history table from design/DEEP_LINK_DIALOG.md. All I/O is supplied by the dialog. */
internal class DeviceDeepLinkPanel(
    private val serial: DeviceSerial,
    private val useCase: DeviceDeepLinkUseCase,
    private val onOpen: (String, String) -> Unit,
    private val onCopy: (String) -> Unit,
) : JPanel() {
    val uriField = DeepLinkField("myapp://product/42?ref=ide").apply { name = "deviceDeepLinkUri" }
    var onLayoutChanged: () -> Unit = {}
    private var synchronizingQuery = false
    /** Set while [loadHistory] rewrites the form, so listeners see one change instead of each step. */
    private var batching = false
    private fun layoutChanged() { if (!batching) onLayoutChanged() }
    internal val queryEditor = QueryParameterEditor { parameters ->
        synchronizingQuery = true
        try { uriField.text = io.github.dkej123.devicecockpit.domain.deeplinks.UriQueryParameters.replace(uriField.text, parameters) }
        finally { synchronizingQuery = false }
        refreshPreview()
        layoutChanged()
    }.apply { onSizeChanged = { layoutChanged() } }
    private val errorLabel = DeepLinkVisuals.label("", 10.5f, color = DeviceCockpitTheme.Colors.red).apply {
        isVisible = false; border = JBUI.Borders.emptyTop(5)
    }
    var selectedPackage = ""
        private set
    var onEdited: () -> Unit = {}
    private var packages = emptyList<PackageEntry>()
    private var loadedFromHistory = false
    /** Oldest at the top, newest at the bottom (user decision, 2026-10-29); the store keeps newest first. */
    private var entries = useCase.history().asReversed()
    private var hoverRow = -1
    private var hoverColumn = -1
    private val recentLabel = caption("")
    private val historyModel = HistoryModel()
    private val history = object : JBTable(historyModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            if (row !in entries.indices) return null
            return when (columnAtPoint(event.point)) {
                4 -> "Open again on this device"
                5 -> "Remove from history"
                else -> historyTooltip(row)
            }
        }
    }.apply {
        name = "deviceDeepLinkHistory"
        rowHeight = JBUI.scale(32)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        showHorizontalLines = true
        intercellSpacing = Dimension(0, JBUI.scale(1))
        showVerticalLines = false
        gridColor = DeviceCockpitTheme.Colors.border
        background = DeviceCockpitTheme.Colors.bg
        tableHeader.reorderingAllowed = false
        tableHeader.font = DeepLinkVisuals.ui(10.5f, true)
        tableHeader.preferredSize = Dimension(0, JBUI.scale(24))
        tableHeader.defaultRenderer = javax.swing.table.TableCellRenderer { _, value, _, _, _, _ ->
            DeepLinkVisuals.label(value?.toString().orEmpty(), 10.5f, true).apply {
                isOpaque = true; background = DeviceCockpitTheme.Colors.header
                border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(DeviceCockpitTheme.Colors.border), JBUI.Borders.empty(0, 0, 0, 10))
            }
        }
        tableHeader.background = DeviceCockpitTheme.Colors.header
        setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(table, value, false, false, row, column)
                val entry = entries[row]
                background = when {
                    entry.uri == uriField.text && entry.packageName == selectedPackage -> DeviceCockpitTheme.Colors.accentBg
                    row == hoverRow -> DeviceCockpitTheme.Colors.hover
                    else -> DeviceCockpitTheme.Colors.bg
                }
                if (column == 1) return DeepLinkUriCell(entry.uri, background)
                foreground = if (column == 4) DeviceCockpitTheme.Colors.accent else if (column >= 3) DeviceCockpitTheme.Colors.textDim else DeviceCockpitTheme.Colors.text
                font = if (column == 1) DeepLinkVisuals.mono(12f) else DeepLinkVisuals.ui(if (column == 2) 11.5f else 11f)
                border = JBUI.Borders.empty(0, if (column == 0) 10 else 0, 0, if (column in 1..3) 10 else 0)
                icon = if (column == 0) historyTile(row) else null
                if (column == 0) text = ""
                horizontalAlignment = if (column >= 4) SwingConstants.CENTER else SwingConstants.LEFT
                return this
            }
        })
        listOf(0 to 36, 2 to 138, 3 to 74, 4 to 24, 5 to 30).forEach { (column, width) ->
            columnModel.getColumn(column).apply { preferredWidth = JBUI.scale(width); maxWidth = JBUI.scale(width) }
        }
        columnModel.getColumn(1).preferredWidth = JBUI.scale(270)
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                hoverRow = rowAtPoint(event.point); hoverColumn = columnAtPoint(event.point); repaint()
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(event: MouseEvent) { hoverRow = -1; hoverColumn = -1; repaint() }
            override fun mouseClicked(event: MouseEvent) {
                val row = rowAtPoint(event.point)
                if (row !in entries.indices) return
                val entry = entries[row]
                when (columnAtPoint(event.point)) {
                    4 -> runHistory(row)
                    5 -> { useCase.remove(entry); refreshHistory() }
                    else -> loadHistory(entry)
                }
            }
        })
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "loadLink")
        actionMap.put("loadLink", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                entries.getOrNull(selectedRow)?.let(::loadHistory)
            }
        })
    }
    private val historyScroll = DeepLinkVisuals.scroll(history).apply {
        setColumnHeaderView(history.tableHeader)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        columnHeader.isOpaque = false
        // The table is laid out only when the dialog shows, so scroll to the newest row then too.
        addHierarchyListener { event ->
            if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                SwingUtilities.invokeLater(::scrollHistoryToNewest)
            }
        }
    }
    private val historyBox = DeepLinkVisuals.surface(DeviceCockpitTheme.Colors.bg, DeviceCockpitTheme.Colors.border, 6).apply {
        layout = BorderLayout(); border = JBUI.Borders.empty(1)
        add(historyScroll, BorderLayout.CENTER)
    }
    internal val handlersRow = DeepLinkHandlersRow({ appLabel(it) }, ::tileIcon, ::chooseFromHandlers).apply { selectedPackage = { this@DeviceDeepLinkPanel.selectedPackage } }
    private val appLinkWarning = DeepLinkAppLinkWarning { selectPackage("") }
    /** `am start -S` (handoff 7 §6); the checkbox lives in the dialog footer. */
    var coldStart = useCase.coldStart
        set(value) { field = value; useCase.coldStart = value; refreshPreview() }
    private var commandExpanded = useCase.commandExpanded
    private val commandToggle = DeepLinkVisuals.button(if (commandExpanded) "Collapse" else "Expand", DesignButtonStyle.LINK) {
        commandExpanded = !commandExpanded
        useCase.commandExpanded = commandExpanded
        refreshPreview()
    }.apply { name = "deviceDeepLinkCommandToggle" }
    private val packageButton = DeepLinkPackageButton().apply { addActionListener { showPackagePicker() } }
    private val preview = DeepLinkCommandArea().apply {
        name = "deviceDeepLinkCommand"
        isEditable = false
        font = DeepLinkVisuals.mono(11f)
        background = DeviceCockpitTheme.Colors.field
        foreground = DeviceCockpitTheme.Colors.text
        border = JBUI.Borders.empty(7, 9)
        lineWrap = false
        rows = 1
    }

    private val commandScroll = DeepLinkVisuals.scroll(preview).apply {
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
    }
    private val commandBox = DeepLinkVisuals.surface(DeviceCockpitTheme.Colors.field, DeviceCockpitTheme.Colors.border, 5).apply {
        layout = BorderLayout(); border = JBUI.Borders.empty(1)
        add(commandScroll, BorderLayout.CENTER)
    }
    init {
        layout = VerticalStackLayout { 0 }
        isOpaque = false
        fun row(component: JComponent) {
            add(component)
        }
        val commandLabel = JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.emptyTop(10)
            add(caption("COMMAND"), BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(commandToggle.apply { preferredSize = Dimension(getFontMetrics(font).stringWidth("Collapse"), JBUI.scale(24)) })
                add(DeepLinkVisuals.button("Copy", DesignButtonStyle.LINK) {
                    if (DeepLinkCommands.isValidUri(uriField.text.trim())) onCopy(useCase.terminalCommand(serial, uriField.text.trim(), selectedPackage, coldStart))
                }.apply { preferredSize = Dimension(preferredSize.width, JBUI.scale(24)) })
            }, BorderLayout.EAST)
        }
        val warningRow = JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.emptyTop(6); add(appLinkWarning) }
        listOf(recentLabel, historyBox, caption("URI"), uriField, errorLabel, handlersRow, queryEditor,
            caption("OPEN IN"), packageButton, warningRow, commandLabel, commandBox).forEach(::row)
        uriField.document.onChange { if (!batching) uriChanged() }
        refreshHistory(); refreshPackageLabel(); refreshPreview(); refreshWarning()
    }

    fun showError(message: String?) {
        errorLabel.text = message.orEmpty()
        errorLabel.isVisible = message != null
        uriField.error = message != null
        revalidate(); repaint()
    }

    private fun uriChanged() {
        batching = true
        try {
            if (!synchronizingQuery) queryEditor.load(uriField.text)
            showError(null)
            refreshPreview(); refreshWarning()
        } finally { batching = false }
        onEdited(); layoutChanged()
    }

    /**
     * Replaces the form in one step. `setText` is a remove plus an insert: handled one by one, the
     * empty URI in between hid the handlers row, warning and parameters, and the dialog shrank and
     * regrew (a visible flicker on every Recent click).
     */
    fun loadHistory(entry: RecentDeviceLink) {
        loadedFromHistory = true
        batching = true
        try {
            selectedPackage = entry.packageName
            uriField.text = entry.uri
            refreshPackageLabel()
        } finally { batching = false }
        uriChanged()
    }

    /** ▸ in Recent: load the row into the form and run it; the dialog stays open (handoff 7 §1). */
    fun runHistory(row: Int) {
        val entry = entries.getOrNull(row) ?: return
        loadHistory(entry)
        onOpen(entry.uri, entry.packageName)
    }

    /** The app a Recent row stands for: its Open in choice, or for Any app the app that took the link last time. */
    private fun shownPackage(entry: RecentDeviceLink) = entry.packageName.ifEmpty { entry.handledBy }

    internal fun historyTile(row: Int): Icon = tileIcon(entries.getOrNull(row)?.let(::shownPackage).orEmpty())

    fun historyTooltip(row: Int): String? = entries.getOrNull(row)?.let { "Click to load into the form · ${it.uri}" }

    fun showHandlers(state: HandlersState) {
        handlersRow.show(state)
        revalidate(); layoutChanged()
    }

    /** "Open in this app" in the handlers popover, "Test as a real tap" ("" = Any app), and any other pick. */
    fun selectPackage(packageName: String) {
        selectedPackage = packageName
        useCase.rememberPackage(packageName)
        refreshPackageLabel(); refreshPreview(); refreshWarning(); onEdited()
    }

    internal fun chooseFromHandlers(packageName: String) = selectPackage(packageName)

    fun setPackages(packages: List<PackageEntry>) {
        this.packages = packages
        if (!loadedFromHistory) selectedPackage = defaultPackage()
        refreshPackageLabel()
        historyModel.fireTableDataChanged()
        refreshPreview()
        refreshWarning()
    }

    fun clearForm() {
        loadedFromHistory = false
        selectedPackage = defaultPackage()
        uriField.text = ""
        queryEditor.load("")
        showError(null)
        refreshPackageLabel()
        refreshPreview()
        refreshWarning()
    }

    /** Any app first, then installed apps; the filter matches label or package, Any app included. */
    internal fun appChoices(query: String): List<PackageEntry> =
        (listOf(anyApp) + packages).filter { query.isBlank() || it.label.contains(query.trim(), true) || it.packageName.contains(query.trim(), true) }

    fun commandText(): String =
        if (commandExpanded) useCase.previewCommand(serial, uriField.text.trim(), selectedPackage, coldStart)
        else useCase.previewLine(serial, uriField.text.trim(), selectedPackage, coldStart)

    private val anyApp = PackageEntry.unresolved("").copy(label = "Any app")
    private fun defaultPackage() = useCase.rememberedPackage().takeIf { pkg -> packages.any { it.packageName == pkg } }.orEmpty()
    internal fun appLabel(pkg: String) = if (pkg.isEmpty()) "Any app" else packages.firstOrNull { it.packageName == pkg }?.label ?: pkg
    private fun refreshPackageLabel() {
        packageButton.showChoice(appLabel(selectedPackage), selectedPackage.ifEmpty { "System chooser" }, tileIcon(selectedPackage))
    }
    private fun refreshWarning() {
        val uri = uriField.text.trim()
        val web = uri.startsWith("http://", true) || uri.startsWith("https://", true)
        val host = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://([^/?#:]*)""").find(uri)?.groupValues?.get(1).orEmpty()
        appLinkWarning.show(appLabel(selectedPackage).takeIf { web && selectedPackage.isNotEmpty() }, host)
        revalidate(); layoutChanged()
    }

    private fun refreshPreview() {
        commandToggle.text = if (commandExpanded) "Collapse" else "Expand"
        preview.text = commandText()
        preview.caretPosition = 0
        val height = JBUI.scale(preview.text.lines().size * 18 + 16)
        commandBox.preferredSize = Dimension(JBUI.scale(562), height)
        commandBox.minimumSize = Dimension(0, height)
        history.repaint()
        revalidate(); layoutChanged()
    }

    fun scrollHistoryToNewest() {
        if (entries.isEmpty()) return
        historyScroll.validate()
        history.scrollRectToVisible(history.getCellRect(entries.lastIndex, 0, true))
    }

    fun refreshHistory() {
        entries = useCase.history().asReversed()
        recentLabel.text = "RECENT · ${entries.size}"
        val height = JBUI.scale((24 + 32 * entries.size).coerceAtMost(230))
        historyScroll.preferredSize = Dimension(JBUI.scale(560), height)
        historyBox.preferredSize = Dimension(JBUI.scale(562), height)
        listOf(recentLabel, historyBox).forEach { it.isVisible = entries.isNotEmpty() }
        historyModel.fireTableDataChanged()
        revalidate(); layoutChanged()
        SwingUtilities.invokeLater(::scrollHistoryToNewest)
    }

    private fun showPackagePicker() {
        val filter = DeepLinkField("Filter installed apps…", 26).apply { name = "deviceDeepLinkAppFilter"; font = DeepLinkVisuals.ui(11f) }
        val model = DefaultListModel<PackageEntry>()
        val noMatch = DeepLinkVisuals.label("No installed app matches", 11f).apply {
            name = "deviceDeepLinkAppNoMatch"; border = JBUI.Borders.empty(8, 6); isVisible = false
        }
        val list = JBList(model).apply {
            visibleRowCount = 5
            cellRenderer = ListCellRenderer { _, value, _, selected, _ ->
                JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    background = if (selected) DeviceCockpitTheme.Colors.accentBg else DeviceCockpitTheme.Colors.panel
                    border = JBUI.Borders.empty(5, 6)
                    add(JLabel(tileIcon(value.packageName)), BorderLayout.WEST)
                    add(JPanel(java.awt.GridLayout(2, 1, 0, 0)).apply {
                        isOpaque = false
                        add(DeepLinkVisuals.label(value.label, 11.5f, true, DeviceCockpitTheme.Colors.text))
                        add(DeepLinkVisuals.label(value.packageName.ifEmpty { "System chooser" }, 10f).apply { font = DeepLinkVisuals.mono(10f) })
                    }, BorderLayout.CENTER)
                    if (value.packageName.isNotEmpty() && value.packageName == useCase.rememberedPackage()) {
                        add(DeepLinkVisuals.label("last used", 10f).apply { font = DeepLinkVisuals.mono(10f) }, BorderLayout.EAST)
                    }
                }
            }
        }

        fun refill() {
            val query = filter.text.trim()
            model.clear()
            appChoices(query).forEach(model::addElement)
            noMatch.isVisible = model.isEmpty
            if (!model.isEmpty) list.selectedIndex = if (query.isEmpty()) (0 until model.size).firstOrNull { model.get(it).packageName == selectedPackage } ?: 0 else 0
        }
        refill()
        filter.document.onChange(::refill)
        val popupPanel = DeepLinkVisuals.surface(DeviceCockpitTheme.Colors.panel, DeviceCockpitTheme.Colors.borderStrong, 4).apply {
            layout = BorderLayout(0, JBUI.scale(4)); border = JBUI.Borders.empty(6)
            add(filter, BorderLayout.NORTH)
            add(DeepLinkVisuals.scroll(list).apply { preferredSize = Dimension(packageButton.width.coerceAtLeast(JBUI.scale(420)), JBUI.scale((model.size * 36).coerceAtMost(180))) }, BorderLayout.CENTER)
            add(noMatch, BorderLayout.SOUTH)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(popupPanel, filter).setRequestFocus(true).setShowBorder(false).setCancelOnClickOutside(true).createPopup()
        fun choose() {
            selectPackage(list.selectedValue?.packageName ?: return)
            popup.cancel()
        }
        list.addMouseListener(object : MouseAdapter() { override fun mouseClicked(event: MouseEvent) { choose() } })
        filter.addActionListener { choose() }
        filter.inputMap.put(KeyStroke.getKeyStroke("DOWN"), "nextApp")
        filter.actionMap.put("nextApp", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) { list.selectedIndex = (list.selectedIndex + 1).coerceAtMost(model.size - 1); list.ensureIndexIsVisible(list.selectedIndex) }
        })
        filter.inputMap.put(KeyStroke.getKeyStroke("UP"), "previousApp")
        filter.actionMap.put("previousApp", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) { list.selectedIndex = (list.selectedIndex - 1).coerceAtLeast(0); list.ensureIndexIsVisible(list.selectedIndex) }
        })
        packageButton.popupOpen = true
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) { packageButton.popupOpen = false }
        })
        popup.showUnderneathOf(packageButton)
    }

    private inner class HistoryModel : AbstractTableModel() {
        override fun getRowCount() = entries.size
        override fun getColumnCount() = 6
        override fun getColumnName(column: Int) = listOf("", "Link", "App", "Last run", "", "")[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val entry = entries[row]
            return when (column) {
                0 -> if (entry.packageName.isEmpty()) "◌" else "▢"
                1 -> entry.uri
                2 -> appLabel(shownPackage(entry))
                3 -> formatTime(entry.lastRun)
                4 -> "▸"
                else -> "×"
            }
        }
    }

    internal fun tileIcon(pkg: String, size: Int = 16): Icon {
        val app = packages.firstOrNull { it.packageName == pkg }
        app?.icon?.let { AppIconImages.icon(it, size) }?.let { return it }
        // Any app, or an app outside the user-app list (system apps such as Chrome): the Android robot.
        if (app == null) return AndroidRobotIcon(size)
        return object : Icon {
            override fun getIconWidth() = JBUI.scale(size)
            override fun getIconHeight() = JBUI.scale(size)
            override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val debug = app?.isDebuggable == true
                    val size = iconWidth - 1
                    if (pkg.isNotEmpty()) {
                        g.color = if (debug) DeviceCockpitTheme.Colors.brandBg else DeviceCockpitTheme.Colors.header
                        g.fillRoundRect(x, y, size, size, JBUI.scale(5), JBUI.scale(5))
                    }
                    g.color = if (debug) DeviceCockpitTheme.Colors.brandBorder else DeviceCockpitTheme.Colors.borderStrong
                    g.stroke = if (pkg.isEmpty()) BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(2f, 2f), 0f) else BasicStroke(1f)
                    g.drawRoundRect(x, y, size, size, JBUI.scale(5), JBUI.scale(5))
                } finally { g.dispose() }
            }
        }
    }

    private fun caption(text: String) = DeepLinkVisuals.caption(text)
    private fun formatTime(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        return when {
            date.toLocalDate() == today -> date.format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH))
            date.toLocalDate() == today.minusDays(1) -> "Yesterday"
            date.toLocalDate().isAfter(today.minusDays(7)) -> date.format(DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH))
            else -> date.format(DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH))
        }
    }
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

private fun javax.swing.text.Document.onChange(action: () -> Unit) = addDocumentListener(object : DocumentListener {
    override fun insertUpdate(event: DocumentEvent?) = action()
    override fun removeUpdate(event: DocumentEvent?) = action()
    override fun changedUpdate(event: DocumentEvent?) = action()
})
