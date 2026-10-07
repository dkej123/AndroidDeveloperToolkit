package dev.acme.adbtoolbox.intellij.display

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.locale.LocaleRow
import dev.acme.adbtoolbox.application.locale.LocaleViewState
import dev.acme.adbtoolbox.application.locale.LocationViewModel
import dev.acme.adbtoolbox.application.locale.LocationViewState
import dev.acme.adbtoolbox.domain.location.EmulatorLocationCommand
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.DesignSections
import dev.acme.adbtoolbox.intellij.ui.common.FlexRowLayout
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipChoice
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipKind
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipRow
import dev.acme.adbtoolbox.intellij.ui.common.RoundedSurface
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Language & region (design §3b): current locale as header meta (amber when it differs from the
 * original), a search over the catalog, the test-locale quick picks, and Reset to original.
 */
class LocaleSection(
    private val onSearch: (String) -> Unit,
    private val onApply: (String) -> Unit,
    private val onReset: () -> Unit,
) : JPanel() {
    private val metaLabel = DesignSections.metaLabel()
    private val searchField = JBTextField().apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        font = AdbToolboxTheme.Typography.body.deriveFont(JBUI.scale(11f))
        emptyText.text = "Search languages and regions…"
        getAccessibleContext().accessibleName = "Search languages and regions"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })
        addActionListener { currentRows.firstOrNull()?.let { onApply(it.tag) } }
    }
    private val clearButton = JButton("×").apply {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        foreground = AdbToolboxTheme.Colors.textFaint
        margin = java.awt.Insets(0, 0, 0, 0)
        isVisible = false
        toolTipText = "Clear"
        addActionListener { searchField.text = "" }
    }
    private val searchWrap = RoundedSurface(AdbToolboxTheme.Colors.field, AdbToolboxTheme.Colors.borderStrong, radius = { AdbToolboxTheme.Radii.field }).apply {
        layout = FlexRowLayout(AdbToolboxTheme.Spacing.s3)
        border = JBUI.Borders.empty(0, JBUI.scale(7))
        preferredSize = Dimension(0, AdbToolboxTheme.Sizes.field)
        add(JBLabel(AllIcons.Actions.Search))
        add(searchField, FlexRowLayout.FILL)
        add(clearButton)
    }
    private val searchRow = object : JPanel(BorderLayout()) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        add(searchWrap, BorderLayout.CENTER)
    }
    private val groupLabel = JBLabel("TEST LOCALES").apply {
        font = AdbToolboxTheme.Typography.groupLabel
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val rowsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val emptyLabel = DesignSections.helpText("")
    private val helpLabel = DesignSections.helpText(
        "Changes the system language; the foreground activity restarts. Pseudo-locales only change strings in builds with pseudoLocalesEnabled.",
    )
    private val originalNote = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUI.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.textFaint
    }
    private val resetLink = DesignButton("Reset to original", DesignButtonStyle.LINK).apply { addActionListener { onReset() } }
    private val overrideRow = flexRow(AdbToolboxTheme.Spacing.s4, originalNote, resetLink, fill = originalNote).apply {
        isVisible = false
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
    }
    private var currentRows: List<LocaleRow> = emptyList()
    private var activeTag: String? = null
    private var enabledForDevice = false

    init {
        layout = BorderLayout()
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(
            DesignSections.section(
                DesignSections.header(DesignSections.titleLabel("Language & region"), metaLabel),
                searchRow,
                DesignSections.inset(groupLabel),
                rowsPanel,
                emptyLabel,
                helpLabel,
                overrideRow,
            ),
            BorderLayout.CENTER,
        )
        emptyLabel.isVisible = false
    }

    private fun changed() {
        clearButton.isVisible = searchField.text.isNotEmpty()
        onSearch(searchField.text)
    }

    /** Stacked in the Device view's vertical box: never taller than its content. */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun update(state: LocaleViewState, deviceOnline: Boolean) {
        enabledForDevice = deviceOnline && !state.loading && state.error == null
        val locale = state.locale
        metaLabel.text = when {
            !deviceOnline || locale == null -> if (state.error != null) "n/a" else "—"
            locale.overridden -> "${locale.current} applied"
            else -> locale.current
        }
        metaLabel.foreground = if (locale?.overridden == true) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint
        metaLabel.toolTipText = state.error
        activeTag = locale?.takeIf { it.overridden }?.current
        groupLabel.text = if (state.searching) "RESULTS" else "TEST LOCALES"
        currentRows = state.rows
        rowsPanel.removeAll()
        state.rows.forEach { row -> rowsPanel.add(LocaleRowView(row, active = row.tag.equals(activeTag, ignoreCase = true), applying = state.applying == row.tag)) }
        emptyLabel.text = "No language matches “${state.query.trim()}”. Search by name or code, e.g. pt-BR."
        emptyLabel.isVisible = state.searching && state.rows.isEmpty()
        originalNote.text = "Original is ${locale?.original ?: "—"}"
        overrideRow.isVisible = locale?.overridden == true
        resetLink.isEnabled = enabledForDevice && state.applying == null
        searchField.isEnabled = deviceOnline
        revalidate()
        repaint()
    }

    internal val rowsForTest: List<Component> get() = rowsPanel.components.toList()
    internal val metaLabelForTest: JBLabel get() = metaLabel
    internal val overrideRowForTest: JPanel get() = overrideRow
    internal val emptyLabelForTest: Component get() = emptyLabel
    internal val searchFieldForTest: JBTextField get() = searchField

    /** A 26px row: mono code (44px) + one-line explanation; the active one is amber with "applied". */
    internal inner class LocaleRowView(val row: LocaleRow, private val active: Boolean, applying: Boolean) : JPanel() {
        private var hovered = false

        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            layout = FlexRowLayout(AdbToolboxTheme.Spacing.s4)
            border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
            preferredSize = Dimension(0, JBUI.scale(26))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
            val code = JBLabel(row.tag).apply {
                font = AdbToolboxTheme.Typography.mono.deriveFont(if (active) Font.BOLD else Font.PLAIN, JBUI.scale(11f))
                foreground = if (active) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.text
                preferredSize = Dimension(JBUI.scale(44), preferredSize.height)
                minimumSize = preferredSize
            }
            val explanation = JBLabel(if (applying) "Applying…" else row.explanation).apply {
                font = AdbToolboxTheme.Typography.caption.deriveFont(JBUI.scale(10.5f))
                foreground = AdbToolboxTheme.Colors.textFaint
                minimumSize = Dimension(0, preferredSize.height)
            }
            add(code)
            add(explanation, FlexRowLayout.FILL)
            if (active) {
                add(JBLabel("applied").apply {
                    font = AdbToolboxTheme.Typography.groupLabel.deriveFont(Font.BOLD, JBUI.scale(9f))
                    foreground = AdbToolboxTheme.Colors.amber
                })
            }
            toolTipText = "${row.tag} — ${row.explanation}"
            getAccessibleContext().accessibleName = row.tag
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val mouse = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (enabledForDevice && !active) onApply(row.tag)
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = getMousePosition(true) != null
                    repaint()
                }
            }
            listOf(this, code, explanation).forEach { it.addMouseListener(mouse) }
        }

        override fun paint(g: Graphics) {
            if (enabledForDevice) return super.paint(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, AdbToolboxTheme.States.disabledOpacity)
                super.paint(g2)
            } finally {
                g2.dispose()
            }
        }

        override fun paintComponent(g: Graphics) {
            val fill = when {
                active -> AdbToolboxTheme.Colors.amberBg
                hovered && enabledForDevice -> AdbToolboxTheme.Colors.hover
                else -> null
            } ?: return
            g.color = fill
            g.fillRect(0, 0, width, height)
        }
    }
}

/**
 * Location (design §3c): city presets and a custom latitude/longitude for an emulator's GPS fix;
 * on a physical device everything is dimmed with the reason. Not an override (no real location to
 * restore), so the selected chip uses the neutral style.
 */
class LocationSection(
    private val onPreset: (String) -> Unit,
    private val onCustom: (String, String) -> Unit,
) : JPanel() {
    private sealed interface Choice {
        data class City(val name: String) : Choice
        data object Custom : Choice
    }

    private val metaLabel = DesignSections.metaLabel(size = 9.5f)
    private val chipRow: PresetChipRow<Choice> = PresetChipRow(
        choices = EmulatorLocationCommand.cities.map { city ->
            PresetChipChoice<Choice>(value = Choice.City(city.name), label = city.name, isDefault = true)
        } + PresetChipChoice(value = Choice.Custom, label = "Custom…", kind = PresetChipKind.CUSTOM),
        selected = Choice.City(EmulatorLocationCommand.cities.first().name),
    ).apply {
        clearSelection()
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset - AdbToolboxTheme.Spacing.s2)
        chips.forEach { chip ->
            chip.toolTipText = when (val value = chip.value) {
                is Choice.City -> EmulatorLocationCommand.cities.first { it.name == value.name }.point.let { p ->
                    val lat = LocationViewModel.format(p.latitude)
                    val lon = LocationViewModel.format(p.longitude)
                    "$lat, $lon — adb emu geo fix $lon $lat"
                }
                Choice.Custom -> "Any latitude and longitude"
            }
        }
        onSelectionChanged = { choice ->
            when (choice) {
                is Choice.City -> {
                    customBlock.isVisible = false
                    onPreset(choice.name)
                }
                Choice.Custom -> customBlock.isVisible = true
            }
        }
    }
    private val latitudeField: JBTextField = coordinateField(84, "latitude")
    private val longitudeField: JBTextField = coordinateField(92, "longitude")
    private val errorLabel: JBLabel = JBLabel("").apply {
        font = AdbToolboxTheme.Typography.caption.deriveFont(JBUI.scale(10.5f))
        foreground = AdbToolboxTheme.Colors.red
        isVisible = false
    }
    private val applyButton: DesignButton = DesignButton("Apply", DesignButtonStyle.SECONDARY).apply { addActionListener { applyCustom() } }
    private val customRow: JPanel = flexRow(
        AdbToolboxTheme.Spacing.s3,
        latitudeField,
        JBLabel(",").apply { font = AdbToolboxTheme.Typography.mono; foreground = AdbToolboxTheme.Colors.textFaint },
        longitudeField,
        applyButton,
    ).apply {
        border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
    }
    private val errorRow = DesignSections.inset(errorLabel)

    /** The custom fields and their errors appear and hide together with the "Custom…" chip. */
    private val customBlock: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
        add(customRow)
        add(errorRow)
    }
    private val helpLabel: dev.acme.adbtoolbox.intellij.ui.common.WrappingText = DesignSections.helpText(EMULATOR_HELP)
    private var emulator = false

    init {
        layout = BorderLayout()
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(
            DesignSections.section(
                DesignSections.header(DesignSections.titleLabel("Location"), metaLabel),
                chipRow,
                customBlock,
                helpLabel,
            ),
            BorderLayout.CENTER,
        )
    }

    private fun coordinateField(width: Int, name: String): JBTextField = JBTextField().apply {
        font = AdbToolboxTheme.Typography.mono
        background = AdbToolboxTheme.Colors.field
        preferredSize = Dimension(JBUI.scale(width), AdbToolboxTheme.Sizes.field)
        emptyText.text = name
        getAccessibleContext().accessibleName = name.replaceFirstChar(Char::uppercase)
        border = BorderFactory.createCompoundBorder(
            SolidChipBorder(AdbToolboxTheme.Colors.borderStrong, radius = { AdbToolboxTheme.Radii.field }),
            JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s3),
        )
        addActionListener { applyCustom() }
    }

    private fun applyCustom(): Unit = onCustom(latitudeField.text, longitudeField.text)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun update(state: LocationViewState, deviceOnline: Boolean) {
        emulator = deviceOnline && state.emulator
        val current = state.current
        metaLabel.text = when {
            !deviceOnline -> "—"
            !state.emulator -> "emulator only"
            current == null -> "not set"
            else -> {
                val coordinates = "${LocationViewModel.format(current.second.latitude)}, ${LocationViewModel.format(current.second.longitude)}"
                current.first?.let { "$it · $coordinates" } ?: coordinates
            }
        }
        val currentChip = current?.first?.let { name -> chipRow.chips.firstOrNull { (it.value as? Choice.City)?.name == name } }
        if (currentChip != null) chipRow.setSelectedValue(currentChip.value) else if (!customBlock.isVisible) chipRow.clearSelection()
        chipRow.applyPending = state.applying
        chipRow.chips.forEach { chip ->
            chip.isEnabled = emulator && !state.applying
            if (deviceOnline && !state.emulator) chip.toolTipText = "Emulator only — a physical device reports its real GPS"
        }
        listOf(latitudeField, longitudeField, applyButton).forEach { it.isEnabled = emulator && !state.applying }
        val errors = listOfNotNull(state.latitudeError, state.longitudeError)
        errorLabel.text = errors.joinToString(" · ")
        errorLabel.isVisible = errors.isNotEmpty()
        latitudeField.foreground = if (state.latitudeError != null) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.text
        longitudeField.foreground = if (state.longitudeError != null) AdbToolboxTheme.Colors.red else AdbToolboxTheme.Colors.text
        helpLabel.text = if (deviceOnline && !state.emulator) {
            "Emulator only — a physical device reports its real GPS. Pick an emulator in the device bar."
        } else {
            EMULATOR_HELP
        }
        revalidate()
        repaint()
    }

    internal val metaLabelForTest: JBLabel get() = metaLabel
    internal val chipRowForTest: PresetChipRow<*> get() = chipRow
    internal val customRowForTest: JPanel get() = customBlock
    internal val errorLabelForTest: JBLabel get() = errorLabel
    internal fun typeCustomForTest(latitude: String, longitude: String) {
        latitudeField.text = latitude
        longitudeField.text = longitude
        applyButton.doClick()
    }

    private companion object {
        const val EMULATOR_HELP = "Sends a GPS fix with adb emu geo fix. It stays until you set another or cold-boot the emulator."
    }
}
