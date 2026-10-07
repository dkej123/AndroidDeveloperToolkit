package dev.acme.adbtoolbox.intellij.apps.details

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.appdetails.AppDetailsIntent
import dev.acme.adbtoolbox.application.appdetails.AppDetailsState
import dev.acme.adbtoolbox.application.appdetails.DatabaseEditorState
import dev.acme.adbtoolbox.application.appdetails.FileAccessState
import dev.acme.adbtoolbox.application.appdetails.PrefsEditorState
import dev.acme.adbtoolbox.domain.appdata.PrefEntry
import dev.acme.adbtoolbox.domain.appdata.PrefType
import dev.acme.adbtoolbox.domain.appdata.PermissionState
import dev.acme.adbtoolbox.domain.appdata.SqlResult
import dev.acme.adbtoolbox.domain.appdata.SqlRows
import dev.acme.adbtoolbox.domain.appdata.SqlValue
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkSource
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.DesignSections
import dev.acme.adbtoolbox.intellij.ui.common.WrappingText
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Image
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * The Apps view's detail page (see [dev.acme.adbtoolbox.application.appdetails.AppDetailsViewModel]):
 * a header with a way back to the list, then Info / Shared prefs / Databases tabs. Every edit is
 * forwarded as an [AppDetailsIntent]; [update] renders the view model's state and is the only thing
 * that changes what the tables show.
 */
class AppDetailsPanel(
    private val onIntent: (AppDetailsIntent) -> Unit,
) : JPanel(BorderLayout()) {

    // ---- header ----

    /** Where "←" returns: the Apps list, or the Device view when opened from Current app (design §3a). */
    private var backToDevice: (() -> Unit)? = null

    private val backLink = DesignButton("← Apps", DesignButtonStyle.LINK).apply {
        addActionListener {
            onIntent(AppDetailsIntent.Close)
            backToDevice?.let { back ->
                showOpenedFromApps()
                back()
            }
        }
    }

    /** Opened from Current app: the back link reads "← Device" and returns there. */
    fun showOpenedFromDevice(back: () -> Unit) {
        backToDevice = back
        backLink.text = "← Device"
        backLink.toolTipText = "Back to the Device view — scroll position is kept"
    }

    fun showOpenedFromApps() {
        backToDevice = null
        backLink.text = "← Apps"
        backLink.toolTipText = null
    }

    internal val backLinkForTest: javax.swing.JButton get() = backLink
    private val iconLabel = JBLabel()
    private val titleLabel = JBLabel().apply {
        font = AdbToolboxTheme.Typography.sectionTitle
        foreground = AdbToolboxTheme.Colors.text
    }
    private val packageLabel = JBLabel().apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val refreshLink = DesignButton("Refresh", DesignButtonStyle.LINK).apply {
        addActionListener { onIntent(AppDetailsIntent.Refresh) }
    }
    private val noticeLabel = JBLabel().apply {
        font = AdbToolboxTheme.Typography.caption
        foreground = AdbToolboxTheme.Colors.brand
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset, AdbToolboxTheme.Spacing.s2, AdbToolboxTheme.Spacing.sectionInset)
    }

    private val header = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.header
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AdbToolboxTheme.Colors.border),
            JBUI.Borders.empty(AdbToolboxTheme.Spacing.s3, 0),
        )
        val spacer = flexSpacer()
        add(flexRow(AdbToolboxTheme.Spacing.s3, backLink, spacer, refreshLink, fill = spacer).apply { border = inset() })
        val titles = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleLabel)
            add(packageLabel)
        }
        add(flexRow(AdbToolboxTheme.Spacing.s3, iconLabel, titles, fill = titles).apply { border = inset() })
        add(noticeLabel)
    }

    // ---- Info tab ----

    private val infoGrid = JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s4, AdbToolboxTheme.Spacing.sectionInset)
    }
    private val permissionsModel = RowsModel(listOf("Permission", "Granted", "Kind"))
    private val permissionsTable = readOnlyTable(permissionsModel)
    private val infoTab = JPanel(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.bg
        add(infoGrid, BorderLayout.NORTH)
        add(JBScrollPane(permissionsTable), BorderLayout.CENTER)
    }

    // ---- Deep links tab ----

    private val analyzeDeepLinks = DesignButton("Analyze APK", DesignButtonStyle.PRIMARY).apply {
        name = "appDetailsAnalyzeDeepLinks"
        addActionListener { onIntent(AppDetailsIntent.AnalyzeDeepLinks) }
    }
    private val deepLinksStatus = statusText()
    private val deepLinkSearch = JTextField().apply {
        name = "appDetailsDeepLinkSearch"
        emptyText("Filter by activity or domain")
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refresh()
            override fun removeUpdate(e: DocumentEvent?) = refresh()
            override fun changedUpdate(e: DocumentEvent?) = refresh()
            private fun refresh() { if (!rendering) lastState?.let(::renderDeepLinks) }
        })
    }
    private val deepLinkUri = JTextField().apply { name = "appDetailsDeepLinkUri"; emptyText("https://example.com/path") }
    private val openDeepLink = DesignButton("Open", DesignButtonStyle.SECONDARY).apply {
        name = "appDetailsOpenDeepLink"
        addActionListener { deepLinkUri.text.trim().takeIf(String::isNotEmpty)?.let { onIntent(AppDetailsIntent.OpenDeepLink(it)) } }
    }
    private val deepLinksModel = RowsModel(listOf("Activity", "Domain", "URI matcher", "Source", "Device", "Warnings"))
    private val deepLinksTable = readOnlyTable(deepLinksModel).apply { name = "appDetailsDeepLinksTable" }
    private val deepLinksTab = JPanel(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.bg
        add(column(
            flexRow(AdbToolboxTheme.Spacing.s3, analyzeDeepLinks).apply { border = inset() },
            flexRow(AdbToolboxTheme.Spacing.s3, deepLinkSearch, fill = deepLinkSearch).apply { border = inset() },
            flexRow(AdbToolboxTheme.Spacing.s3, deepLinkUri, openDeepLink, fill = deepLinkUri).apply { border = inset() },
            deepLinksStatus,
        ), BorderLayout.NORTH)
        add(JBScrollPane(deepLinksTable), BorderLayout.CENTER)
    }

    // ---- Permissions tab ----

    private val managedPermissionsModel = RowsModel(listOf("Permission", "Type", "State", "Flags"))
    private val managedPermissionsTable = readOnlyTable(managedPermissionsModel).apply {
        name = "appDetailsPermissionsTable"
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        selectionModel.addListSelectionListener { event -> if (!event.valueIsAdjusting) updatePermissionActions(lastState) }
    }
    private val grantPermission = DesignButton("Grant", DesignButtonStyle.SECONDARY).apply {
        addActionListener { selectedPermission()?.let { onIntent(AppDetailsIntent.GrantPermission(it)) } }
    }
    private val revokePermission = DesignButton("Revoke", DesignButtonStyle.SECONDARY).apply {
        addActionListener { selectedPermission()?.let { onIntent(AppDetailsIntent.RevokePermission(it)) } }
    }
    private val resetPermission = DesignButton("Reset decision", DesignButtonStyle.SECONDARY).apply {
        addActionListener { selectedPermission()?.let { onIntent(AppDetailsIntent.ResetPermission(it)) } }
    }
    private val permissionsStatus = statusText()
    private val permissionsTab = JPanel(BorderLayout()).apply {
        background = AdbToolboxTheme.Colors.bg
        add(permissionsStatus, BorderLayout.NORTH)
        add(JBScrollPane(managedPermissionsTable), BorderLayout.CENTER)
        add(buttonRow(grantPermission, revokePermission, null, resetPermission), BorderLayout.SOUTH)
    }

    // ---- Shared prefs tab ----

    private val prefsFiles = ComboBox<String>().apply {
        name = "appDetailsPrefsFiles"
        addActionListener { if (!rendering) (selectedItem as? String)?.let { if (it != shownPrefsFile) onIntent(AppDetailsIntent.OpenPrefs(it)) } }
    }
    private var shownPrefsFile: String? = null
    private val prefsModel = PrefsTableModel { originalKey, key, type, value -> onIntent(AppDetailsIntent.PutPref(originalKey, key, type, value)) }
    private val prefsTable = JBTable(prefsModel).apply {
        name = "appDetailsPrefsTable"
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(1).cellEditor = DefaultCellEditor(ComboBox(PrefType.entries.toTypedArray()))
        columnModel.getColumn(1).maxWidth = JBUI.scale(90)
        emptyText.text = "No entries"
    }
    private val prefsAdd = DesignButton("Add", DesignButtonStyle.SECONDARY).apply {
        addActionListener { onIntent(AppDetailsIntent.PutPref(null, uniqueKey(), PrefType.Text, "")) }
    }
    private val prefsRemove = DesignButton("Remove", DesignButtonStyle.SECONDARY).apply {
        addActionListener { prefsModel.keyAt(prefsTable.selectedRow)?.let { onIntent(AppDetailsIntent.RemovePref(it)) } }
    }
    private val prefsRevert = DesignButton("Revert", DesignButtonStyle.SECONDARY).apply {
        addActionListener { onIntent(AppDetailsIntent.RevertPrefs) }
    }
    private val prefsSave = DesignButton("Save to device", DesignButtonStyle.PRIMARY).apply {
        name = "appDetailsSavePrefs"
        toolTipText = "Force-stops the app, then writes the file"
        addActionListener { stopEditing(prefsTable); onIntent(AppDetailsIntent.SavePrefs) }
    }
    private val prefsStatus = statusText()
    private val prefsEditor = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(column(fileRow("File", prefsFiles), prefsStatus), BorderLayout.NORTH)
        add(JBScrollPane(prefsTable), BorderLayout.CENTER)
        add(buttonRow(prefsAdd, prefsRemove, null, prefsRevert, prefsSave), BorderLayout.SOUTH)
    }
    private val prefsTab = accessCard(prefsEditor)

    // ---- Databases tab ----

    private val databaseFiles = ComboBox<String>().apply {
        name = "appDetailsDatabaseFiles"
        addActionListener { if (!rendering) (selectedItem as? String)?.let { if (it != shownDatabase) onIntent(AppDetailsIntent.OpenDatabase(it)) } }
    }
    private var shownDatabase: String? = null
    private val tables = ComboBox<String>().apply {
        name = "appDetailsTables"
        addActionListener { if (!rendering) (selectedItem as? String)?.let { if (it != shownTable) onIntent(AppDetailsIntent.ShowTable(it)) } }
    }
    private var shownTable: String? = null
    private var shownRows: SqlRows? = null
    private val rowsModel = RowsModel(emptyList()) { rowIndex, column, value -> onIntent(AppDetailsIntent.EditCell(rowIndex, column, value)) }
    private val rowsTable = JBTable(rowsModel).apply {
        name = "appDetailsRowsTable"
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        emptyText.text = "No rows"
        toolTipText = "Double-click a cell to edit; type NULL for SQL NULL"
    }
    private val pageLabel = JBLabel().apply {
        font = AdbToolboxTheme.Typography.monoMeta
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private var offset = 0L
    private val previousPage = DesignButton("‹", DesignButtonStyle.SECONDARY).apply {
        addActionListener { onIntent(AppDetailsIntent.ShowPage(offset - PAGE)) }
    }
    private val nextPage = DesignButton("›", DesignButtonStyle.SECONDARY).apply {
        addActionListener { onIntent(AppDetailsIntent.ShowPage(offset + PAGE)) }
    }
    private val sqlField = JTextField().apply {
        font = AdbToolboxTheme.Typography.mono
        emptyText("SELECT * FROM … / UPDATE …")
        addActionListener { runSql() }
    }
    private val runSql = DesignButton("Run", DesignButtonStyle.SECONDARY).apply { addActionListener { runSql() } }
    private val queryModel = RowsModel(emptyList())
    private val queryTable = readOnlyTable(queryModel).apply { autoResizeMode = JTable.AUTO_RESIZE_OFF }
    private val queryStatus = statusText()
    private val databaseRevert = DesignButton("Revert", DesignButtonStyle.SECONDARY).apply {
        addActionListener { onIntent(AppDetailsIntent.RevertDatabase) }
    }
    private val databaseSave = DesignButton("Save to device", DesignButtonStyle.PRIMARY).apply {
        name = "appDetailsSaveDatabase"
        toolTipText = "Force-stops the app, then replaces the database on the device"
        addActionListener { stopEditing(rowsTable); onIntent(AppDetailsIntent.SaveDatabase) }
    }
    private val databaseStatus = statusText()
    private val databaseEditor = JPanel(BorderLayout()).apply {
        isOpaque = false
        val pager = flexRow(AdbToolboxTheme.Spacing.s2, previousPage, nextPage, pageLabel)
        add(column(fileRow("Database", databaseFiles), fileRow("Table", tables), databaseStatus), BorderLayout.NORTH)
        add(JBScrollPane(rowsTable), BorderLayout.CENTER)
        val sqlRow = flexRow(AdbToolboxTheme.Spacing.s3, sqlField, runSql, fill = sqlField)
        val south = column(
            pager.apply { border = inset() },
            sqlRow.apply { border = inset() },
            queryStatus,
            JBScrollPane(queryTable).apply { preferredSize = Dimension(0, JBUI.scale(110)) },
            buttonRow(null, null, null, databaseRevert, databaseSave),
        )
        add(south, BorderLayout.SOUTH)
    }
    private val databaseTab = accessCard(databaseEditor)

    private val tabs = JBTabbedPane().apply {
        name = "appDetailsTabs"
        addTab("Info", infoTab)
        addTab("Deep Links", deepLinksTab)
        addTab("Permissions", permissionsTab)
        addTab("Shared prefs", prefsTab)
        addTab("Databases", databaseTab)
    }

    init {
        background = AdbToolboxTheme.Colors.bg
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    // ---- test hooks ----
    internal val prefsModelForTest: PrefsTableModel get() = prefsModel
    internal val rowsModelForTest: RowsModel get() = rowsModel
    internal val prefsSaveForTest: DesignButton get() = prefsSave
    internal val databaseSaveForTest: DesignButton get() = databaseSave
    internal val titleForTest: String get() = titleLabel.text
    internal val tabsForTest: JBTabbedPane get() = tabs
    internal val analyzeDeepLinksForTest: DesignButton get() = analyzeDeepLinks
    internal val deepLinksRowCountForTest: Int get() = deepLinksModel.rowCount
    internal fun deepLinksValueForTest(row: Int, column: Int): String = deepLinksModel.getValueAt(row, column).toString()
    internal val permissionsRowCountForTest: Int get() = managedPermissionsModel.rowCount
    internal fun accessMessageForTest(): String = (prefsTab.getClientProperty(ACCESS_MESSAGE) as WrappingText).text
    internal val infoTextForTest: String
        get() = infoGrid.components.filterIsInstance<JBLabel>().joinToString("\n") { it.text }

    /** Set while [update] writes into the controls, so their listeners do not echo intents back. */
    private var rendering = false
    private var lastState: AppDetailsState? = null

    /** The package whose first prefs file / database was already opened automatically. */
    private var autoOpenedFor: String? = null

    fun update(state: AppDetailsState) {
        lastState = state
        rendering = true
        try {
            render(state)
        } finally {
            rendering = false
        }
        autoOpen(state)
    }

    /** Opens the first prefs file and database once per app, so the tabs are not empty on arrival. */
    private fun autoOpen(state: AppDetailsState) {
        val packageName = state.packageName ?: return
        if (autoOpenedFor == packageName || state.access !is FileAccessState.Available) return
        autoOpenedFor = packageName
        if (state.prefs == null) state.sharedPrefsFiles.firstOrNull()?.let { onIntent(AppDetailsIntent.OpenPrefs(it)) }
        if (state.database == null) state.databaseFiles.firstOrNull()?.let { onIntent(AppDetailsIntent.OpenDatabase(it)) }
    }

    private fun render(state: AppDetailsState) {
        if (state.packageName == null) autoOpenedFor = null
        titleLabel.text = state.label ?: state.packageName.orEmpty()
        packageLabel.text = listOfNotNull(state.packageName, state.details?.versionName?.let { "v$it" }).joinToString(" · ")
        iconLabel.icon = state.icon?.let { icon ->
            runCatching { javax.imageio.ImageIO.read(icon.png.inputStream()) }.getOrNull()
                ?.getScaledInstance(JBUI.scale(28), JBUI.scale(28), Image.SCALE_SMOOTH)
                ?.let(::ImageIcon)
        }
        noticeLabel.text = state.notice.orEmpty()
        noticeLabel.isVisible = state.notice != null
        renderInfo(state)
        renderDeepLinks(state)
        renderPermissions(state)
        renderAccess(state.access)
        renderPrefs(state.sharedPrefsFiles, state.prefs)
        renderDatabase(state.databaseFiles, state.database)
        revalidate()
        repaint()
    }

    private fun renderDeepLinks(state: AppDetailsState) {
        val analysis = state.deepLinks
        val query = deepLinkSearch.text.trim().lowercase()
        deepLinksModel.setRows(analysis?.catalog?.targets.orEmpty().flatMap { target ->
            target.patterns.map { pattern ->
                val authority = pattern.hosts.joinToString(" | ")
                val uri = "${pattern.schemes.joinToString(" | ")}://$authority" +
                    pattern.paths.joinToString(" | ") { it.value }
                val verifications = pattern.hosts.mapNotNull { host -> analysis?.verifications?.firstOrNull { it.host == host } }
                val device = verifications.map { it.deviceState.name.lowercase().replaceFirstChar(Char::uppercase) }
                    .distinct().joinToString()
                val warnings = buildList {
                    if (!target.hasDefaultCategory) add("Missing DEFAULT")
                    if (DeepLinkSource.RUNTIME_UNKNOWN in target.sources) add("Runtime unknown")
                    verifications.filter { it.stale }.forEach { verification ->
                        add("Stale validation${verification.validatedAtEpochMillis?.let { " from $it" }.orEmpty()}")
                    }
                    addAll(verifications.mapNotNull { it.error })
                }.distinct().joinToString(" · ")
                val sources = target.sources.sortedBy(DeepLinkSource::ordinal).joinToString(" · ") { source ->
                    source.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
                }
                listOf(target.componentName, authority, uri, sources, device, warnings)
            }
        }.filter { row -> query.isEmpty() || row.any { it.lowercase().contains(query) } })
        analyzeDeepLinks.isEnabled = !state.deepLinksLoading
        openDeepLink.isEnabled = !state.deepLinksLoading && analysis != null
        deepLinksStatus.text = when {
            state.deepLinksLoading -> "Reading installed APK and split manifests…"
            state.deepLinksError != null -> state.deepLinksError
            analysis == null -> "Analysis runs only on demand. Cached results are restored when available."
            else -> analysis.catalog.targets.size.let { n -> "$n ${if (n == 1) "target" else "targets"}" } +
                " · ${if (analysis.fromCache) "cached" else "analyzed"}"
        }
        deepLinksStatus.foreground = if (state.deepLinksError != null) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.textFaint
    }

    private fun renderPermissions(state: AppDetailsState) {
        managedPermissionsModel.setRows(state.details?.permissions.orEmpty().map { permission ->
            listOf(permission.name, permission.kind.name.lowercase(), permission.state.name.lowercase().replace('_', ' '), permission.flags.joinToString { it.name })
        })
        val selected = selectedPermission()?.let { name -> state.details?.permissions?.firstOrNull { it.name == name } }
        updatePermissionActions(state)
        permissionsStatus.text = when {
            state.permissionBusy != null -> "Applying ${state.permissionBusy}…"
            else -> "Android user ${state.androidUserId} · fixed install/signature/policy permissions are read-only"
        }
    }

    private fun updatePermissionActions(state: AppDetailsState?) {
        val selected = selectedPermission()?.let { name -> state?.details?.permissions?.firstOrNull { it.name == name } }
        val mutable = selected?.mutable == true && state?.permissionBusy == null
        grantPermission.isEnabled = mutable && selected?.state !in setOf(PermissionState.GRANTED, PermissionState.GRANTED_ONE_TIME)
        revokePermission.isEnabled = mutable && selected?.state in setOf(PermissionState.GRANTED, PermissionState.GRANTED_ONE_TIME)
        resetPermission.isEnabled = mutable
    }

    private fun selectedPermission(): String? = managedPermissionsTable.selectedRow.takeIf { it >= 0 }
        ?.let { managedPermissionsModel.getValueAt(it, 0) as? String }

    private fun renderInfo(state: AppDetailsState) {
        val d = state.details
        val rows = listOf(
            "Version" to d?.versionName,
            "Version code" to d?.versionCode,
            "Min / target SDK" to listOfNotNull(d?.minSdk, d?.targetSdk).joinToString(" / ").ifEmpty { null },
            "UID" to d?.uid,
            "Debuggable" to d?.let { if (it.isDebuggable) "yes" else "no" },
            "Process" to (state.runningPid?.let { "running · pid $it" } ?: "not running"),
            "Installed" to d?.firstInstallTime,
            "Updated" to d?.lastUpdateTime,
            "Installer" to d?.installer,
            "ABI" to d?.primaryAbi,
            "APK path" to d?.codePath,
            "Data dir" to d?.dataDir,
            "File access" to (state.access as? FileAccessState.Available)?.access?.label,
            "Flags" to d?.flags?.joinToString(" ")?.ifEmpty { null },
        )
        infoGrid.removeAll()
        rows.forEachIndexed { index, (key, value) ->
            infoGrid.add(JBLabel(key.uppercase()).apply {
                font = AdbToolboxTheme.Typography.groupLabel
                foreground = AdbToolboxTheme.Colors.textFaint
            }, gbc(0, index, 0.0))
            infoGrid.add(JBLabel(value ?: "—").apply {
                font = AdbToolboxTheme.Typography.mono
                foreground = AdbToolboxTheme.Colors.text
                toolTipText = value
            }, gbc(1, index, 1.0))
        }
        state.detailsError?.let { infoGrid.add(JBLabel(it).apply { foreground = AdbToolboxTheme.Colors.red }, gbc(0, rows.size, 1.0, width = 2)) }
        permissionsModel.setRows(d?.permissions.orEmpty().map { p ->
            listOf(p.name, when (p.granted) { true -> "granted"; false -> "denied"; null -> "requested" }, if (p.runtime) "runtime" else "install")
        })
    }

    private fun renderAccess(access: FileAccessState) {
        listOf(prefsTab, databaseTab).forEach { card ->
            val message = card.getClientProperty(ACCESS_MESSAGE) as WrappingText
            when (access) {
                FileAccessState.Checking -> {
                    message.text = "Checking file access…"
                    (card.layout as CardLayout).show(card, MESSAGE)
                }
                is FileAccessState.Unavailable -> {
                    message.text = access.reason
                    (card.layout as CardLayout).show(card, MESSAGE)
                }
                is FileAccessState.Available -> (card.layout as CardLayout).show(card, EDITOR)
            }
        }
    }

    private fun renderPrefs(files: List<String>, prefs: PrefsEditorState?) {
        syncCombo(prefsFiles, files, prefs?.fileName)
        shownPrefsFile = prefs?.fileName
        prefsModel.setEntries(prefs?.entries.orEmpty())
        val busy = prefs == null || prefs.loading || prefs.saving
        prefsAdd.isEnabled = !busy
        prefsRemove.isEnabled = !busy
        prefsRevert.isEnabled = !busy && prefs?.dirty == true
        prefsSave.isEnabled = !busy && prefs?.dirty == true
        prefsStatus.text = when {
            files.isEmpty() -> "This app has no shared_prefs files."
            prefs?.error != null -> prefs.error!!
            prefs?.saving == true -> "Saving…"
            prefs?.dirty == true -> "Unsaved changes — Save to device force-stops the app first."
            else -> "Double-click a value to edit it."
        }
        prefsStatus.foreground = if (prefs?.error != null) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.textFaint
    }

    private fun renderDatabase(files: List<String>, database: DatabaseEditorState?) {
        syncCombo(databaseFiles, files, database?.fileName)
        shownDatabase = database?.fileName
        syncCombo(tables, database?.tables.orEmpty(), database?.table)
        shownTable = database?.table
        val page = database?.page
        if (page != shownRows) {
            shownRows = page
            rowsModel.setColumns(page?.columns.orEmpty(), editable = page?.rowIds != null)
            rowsModel.setRows(page?.rows.orEmpty().map { row -> row.map(SqlValue::display) })
        }
        offset = database?.offset ?: 0
        val total = page?.totalRows ?: 0
        pageLabel.text = if (page == null || total == 0L) "" else "${offset + 1}–${offset + page.rows.size} of $total"
        previousPage.isEnabled = offset > 0
        nextPage.isEnabled = page != null && offset + page.rows.size < total
        when (val result = database?.queryResult) {
            is SqlResult.Rows -> {
                queryModel.setColumns(result.rows.columns, editable = false)
                queryModel.setRows(result.rows.rows.map { row -> row.map(SqlValue::display) })
                queryStatus.text = "${result.rows.rows.size} rows"
            }
            is SqlResult.Updated -> queryStatus.text = "${result.count} rows changed (local copy — Save to device to apply)"
            is SqlResult.Failed -> queryStatus.text = result.message
            null -> queryStatus.text = ""
        }
        queryStatus.foreground = if (database?.queryResult is SqlResult.Failed) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.textFaint
        val busy = database == null || database.loading || database.saving
        runSql.isEnabled = !busy
        databaseRevert.isEnabled = !busy && database?.dirty == true
        databaseSave.isEnabled = !busy && database?.dirty == true
        databaseStatus.text = when {
            files.isEmpty() -> "This app has no databases."
            database?.error != null -> database.error!!
            database?.loading == true -> "Copying the database from the device…"
            database?.saving == true -> "Saving…"
            database?.dirty == true -> "Unsaved changes (edited on a local copy) — Save to device force-stops the app first."
            page != null && page.rowIds == null -> "This table has no rowid, so its rows are read-only here; use SQL to change it."
            else -> "Edits apply to a local copy until you save."
        }
        databaseStatus.foreground = if (database?.error != null) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.textFaint
    }

    private fun runSql() {
        sqlField.text.trim().takeIf { it.isNotEmpty() }?.let { onIntent(AppDetailsIntent.RunSql(it)) }
    }

    private fun uniqueKey(): String {
        val keys = prefsModel.keys()
        return generateSequence(1) { it + 1 }.map { "new_key_$it" }.first { it !in keys }
    }

    private fun syncCombo(combo: ComboBox<String>, items: List<String>, selected: String?) {
        val current = (0 until combo.itemCount).map { combo.getItemAt(it) }
        if (current != items) {
            combo.removeAllItems()
            items.forEach(combo::addItem)
        }
        if (selected != null && combo.selectedItem != selected) combo.selectedItem = selected
    }

    private fun accessCard(editor: JComponent): JPanel {
        val message = WrappingText("", AdbToolboxTheme.Typography.body, AdbToolboxTheme.Colors.textDim).apply {
            border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s6)
        }
        return JPanel(CardLayout()).apply {
            background = AdbToolboxTheme.Colors.bg
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(message, BorderLayout.NORTH) }, MESSAGE)
            add(editor, EDITOR)
            putClientProperty(ACCESS_MESSAGE, message)
        }
    }

    private fun column(vararg children: JComponent): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s2, 0)
        children.forEach { child ->
            child.alignmentX = Component.LEFT_ALIGNMENT
            add(child)
        }
    }

    private fun fileRow(label: String, combo: ComboBox<String>): JComponent {
        val caption = JBLabel(label).apply {
            font = AdbToolboxTheme.Typography.caption
            foreground = AdbToolboxTheme.Colors.textDim
            preferredSize = Dimension(JBUI.scale(58), preferredSize.height)
        }
        return flexRow(AdbToolboxTheme.Spacing.s3, caption, combo, fill = combo).apply { border = JBUI.Borders.empty(2, AdbToolboxTheme.Spacing.sectionInset) }
    }

    private fun buttonRow(vararg buttons: JComponent?): JComponent {
        val spacer = flexSpacer()
        val left = buttons.take(2).filterNotNull()
        val right = buttons.drop(3).filterNotNull()
        return flexRow(AdbToolboxTheme.Spacing.s3, *(left + spacer + right).toTypedArray(), fill = spacer).apply {
            border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s3, AdbToolboxTheme.Spacing.sectionInset)
        }
    }

    private fun statusText() = WrappingText("", AdbToolboxTheme.Typography.caption, AdbToolboxTheme.Colors.textFaint).apply {
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset)
    }

    private fun inset() = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)

    private fun readOnlyTable(model: AbstractTableModel) = JBTable(model).apply { setShowGrid(false) }

    private fun stopEditing(table: JTable) {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    private fun JTextField.emptyText(text: String) {
        toolTipText = text
        putClientProperty("JTextField.placeholderText", text)
    }

    private fun gbc(x: Int, y: Int, weight: Double, width: Int = 1) = GridBagConstraints().apply {
        gridx = x
        gridy = y
        gridwidth = width
        weightx = weight
        anchor = GridBagConstraints.WEST
        fill = if (x == 1) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
        insets = JBUI.insets(1, 0, 1, AdbToolboxTheme.Spacing.s4)
    }

    private companion object {
        const val PAGE = 100L
        const val MESSAGE = "message"
        const val EDITOR = "editor"
        const val ACCESS_MESSAGE = "adbToolbox.accessMessage"
    }
}

/** Key / Type / Value rows; a finished edit is reported through [onEdit], never applied locally. */
internal class PrefsTableModel(
    private val onEdit: (originalKey: String, key: String, type: PrefType, value: String) -> Unit,
) : AbstractTableModel() {
    private var entries: List<PrefEntry> = emptyList()

    fun setEntries(value: List<PrefEntry>) {
        if (value == entries) return
        entries = value
        fireTableDataChanged()
    }

    fun keyAt(row: Int): String? = entries.getOrNull(row)?.key

    fun keys(): Set<String> = entries.map { it.key }.toSet()

    override fun getRowCount() = entries.size
    override fun getColumnCount() = 3
    override fun getColumnName(column: Int) = listOf("Key", "Type", "Value")[column]
    override fun isCellEditable(row: Int, column: Int) = entries[row].value.type != null
    override fun getValueAt(row: Int, column: Int): Any = entries[row].let { entry ->
        when (column) {
            0 -> entry.key
            1 -> entry.value.type ?: "null"
            else -> entry.value.display
        }
    }

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val entry = entries.getOrNull(row) ?: return
        val type = entry.value.type ?: return
        when (column) {
            0 -> onEdit(entry.key, value.toString(), type, entry.value.display)
            1 -> onEdit(entry.key, entry.key, value as? PrefType ?: return, entry.value.display)
            else -> onEdit(entry.key, entry.key, type, value.toString())
        }
    }
}

/** A plain string grid; when [editable], a finished cell edit is reported through [onEdit] ("NULL" = SQL NULL). */
internal class RowsModel(
    private var columns: List<String>,
    private val onEdit: (rowIndex: Int, column: String, value: String?) -> Unit = { _, _, _ -> },
) : AbstractTableModel() {
    private var rows: List<List<String>> = emptyList()
    private var editable = false

    fun setColumns(value: List<String>, editable: Boolean) {
        this.editable = editable
        if (value != columns) {
            columns = value
            fireTableStructureChanged()
        }
    }

    fun setRows(value: List<List<String>>) {
        rows = value
        fireTableDataChanged()
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun isCellEditable(row: Int, column: Int) = editable && !rows[row][column].startsWith("BLOB (")
    override fun getValueAt(row: Int, column: Int): Any = rows[row][column]
    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val text = value?.toString() ?: return
        if (text == rows[row][column]) return
        onEdit(row, columns[column], if (text == "NULL") null else text)
    }
}
