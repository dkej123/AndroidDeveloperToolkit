package dev.acme.adbtoolbox.intellij.display

import dev.acme.adbtoolbox.domain.display.TalkBackProfile

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.acme.adbtoolbox.application.display.QuickToggleFieldState
import dev.acme.adbtoolbox.application.display.QuickTogglesViewState
import dev.acme.adbtoolbox.application.display.developer.DeveloperOptionsViewState
import dev.acme.adbtoolbox.application.display.developer.DeveloperToggle
import dev.acme.adbtoolbox.application.display.toggles.DeviceSettingToggle
import dev.acme.adbtoolbox.application.display.toggles.DeviceSettingTogglesViewState
import dev.acme.adbtoolbox.domain.display.toggles.ScreenRotation
import dev.acme.adbtoolbox.application.display.valueOrNull
import dev.acme.adbtoolbox.domain.display.developer.BackgroundProcessLimit
import dev.acme.adbtoolbox.application.display.density.DensityViewState
import dev.acme.adbtoolbox.domain.display.AnimationsSummary
import dev.acme.adbtoolbox.domain.display.density.DensityPresets
import dev.acme.adbtoolbox.domain.display.density.percentToDpi
import dev.acme.adbtoolbox.domain.display.fontscale.FontScalePresets
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleRange
import dev.acme.adbtoolbox.domain.display.fontscale.FontScaleState
import dev.acme.adbtoolbox.domain.display.fontscale.formatFontScale
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipChoice
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipKind
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipRow
import dev.acme.adbtoolbox.intellij.ui.common.PresetChipRowStyle
import dev.acme.adbtoolbox.intellij.ui.common.DesignButton
import dev.acme.adbtoolbox.intellij.ui.common.DesignButtonStyle
import dev.acme.adbtoolbox.intellij.ui.common.DesignSections
import dev.acme.adbtoolbox.intellij.ui.common.SolidChipBorder
import dev.acme.adbtoolbox.intellij.ui.common.flexRow
import dev.acme.adbtoolbox.intellij.ui.common.flexSpacer
import dev.acme.adbtoolbox.intellij.ui.common.ToggleSwitch
import dev.acme.adbtoolbox.intellij.ui.common.ViewportWidthPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.JTextField
import javax.swing.JToggleButton

/**
 * Task 046's final visual design for the Display view (`design/README.md` §5): Font scale/Display
 * scale sections (chip row + dashed "Custom…" disclosure + override note/reset link) built on the
 * same [PresetChipRow] the Design System already supplies, and a Quick toggles section built on
 * [ToggleSwitch]. Every header meta value and toggle value text is rendered only from a device
 * readback ([FontScaleState]/[DensityViewState]/[QuickTogglesViewState]) — never from the value a
 * control was last clicked with — so the view can never visually claim an unconfirmed value.
 */
class DisplayPanel(
    private val onApplyFontScale: (Double) -> Unit,
    private val onResetFontScale: () -> Unit,
    private val onApplyDensityPreset: (Int) -> Unit,
    private val onApplyCustomDensity: (Int) -> Unit,
    private val onResetDensity: () -> Unit,
    onSetDarkTheme: (Boolean) -> Unit,
    onSetShowTouches: (Boolean) -> Unit,
    onSetAnimationsOff: (Boolean) -> Unit,
    onSetTalkBack: (Boolean) -> Unit = {},
    private val onSetDeveloperToggle: (DeveloperToggle, Boolean) -> Unit = { _, _ -> },
    private val onSetProcessLimit: (Int) -> Unit = {},
    private val onSetDeviceSettingToggle: (DeviceSettingToggle, Boolean) -> Unit = { _, _ -> },
    private val onSetRotation: (ScreenRotation) -> Unit = {},
    /** Mounted inside another scrolling view (the Device view) rather than scrolling on its own. */
    embedded: Boolean = false,
) : JBPanel<DisplayPanel>(BorderLayout()) {

    private sealed interface FontChoice {
        data class Preset(val value: Double) : FontChoice
        data object Custom : FontChoice
    }

    private sealed interface DensityChoice {
        data class Preset(val percent: Int) : DensityChoice
        data object Custom : DensityChoice
    }

    // ---- Font scale section (`design/README.md` §5.1) ----

    private val fontTitleLabel = sectionTitleLabel("Font scale")
    private val fontMetaLabel = sectionMetaLabel()
    private val fontHeader = sectionHeader(fontTitleLabel, fontMetaLabel)

    private val fontChipRow: PresetChipRow<FontChoice> = PresetChipRow(
        choices = FontScalePresets.VALUES.map { value ->
            PresetChipChoice<FontChoice>(
                value = FontChoice.Preset(value),
                label = fontChipLabel(value),
                isDefault = value == FontScalePresets.DEFAULT,
            )
        } + PresetChipChoice(
            value = FontChoice.Custom,
            label = "Custom…",
            kind = PresetChipKind.CUSTOM,
        ),
        selected = FontChoice.Preset(FontScalePresets.DEFAULT),
    ).apply {
        // FlowLayout adds one hgap before the first chip; keep the chips on the 10px section inset.
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset - AdbToolboxTheme.Spacing.s2)
        chips.forEach { chip ->
            chip.toolTipText = when (val value = chip.value) {
                is FontChoice.Preset -> if (value.value == FontScalePresets.DEFAULT) {
                    "Device default"
                } else {
                    "settings put system font_scale ${formatFontScale(value.value)}"
                }
                FontChoice.Custom -> "Enter any scale between ${formatFontScale(FontScaleRange.MIN)} and ${formatFontScale(FontScaleRange.MAX)}"
            }
        }
        onSelectionChanged = { choice ->
            when (choice) {
                is FontChoice.Preset -> {
                    fontCustomRow.isVisible = false
                    onApplyFontScale(choice.value)
                }
                FontChoice.Custom -> fontCustomRow.isVisible = true
            }
        }
    }

    private val fontCustomField = customField()
    private val fontCustomErrorLabel = errorLabel()
    private val fontCustomRow = customDisclosureRow(
        field = fontCustomField,
        unit = "×",
        errorLabel = fontCustomErrorLabel,
        onApply = ::applyFontCustomFromField,
    )

    private val fontOverrideNoteLabel = overrideNoteLabel("Overriding device default (${fontChipLabel(FontScalePresets.DEFAULT)})")
    private val fontResetLink = linkButton("Reset") { onResetFontScale() }
    private val fontOverrideRow = overrideRow(fontOverrideNoteLabel, fontResetLink)

    private val fontSection = section(fontHeader, fontChipRow, fontCustomRow, fontOverrideRow)

    // ---- Display scale (density) section (`design/README.md` §5.2) ----

    private val densityTitleLabel = sectionTitleLabel("Display scale")
    private val densityMetaLabel = sectionMetaLabel()
    private val densityHeader = sectionHeader(densityTitleLabel, densityMetaLabel)

    private val densityChipRow: PresetChipRow<DensityChoice> = PresetChipRow(
        choices = DensityPresets.PERCENTAGES.map { percent ->
            PresetChipChoice<DensityChoice>(
                value = DensityChoice.Preset(percent),
                label = "$percent%",
                isDefault = percent == 100,
            )
        } + PresetChipChoice(
            value = DensityChoice.Custom,
            label = "Custom…",
            kind = PresetChipKind.CUSTOM,
        ),
        selected = DensityChoice.Preset(100),
    ).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset - AdbToolboxTheme.Spacing.s2)
        onSelectionChanged = { choice ->
            when (choice) {
                is DensityChoice.Preset -> {
                    densityCustomRow.isVisible = false
                    onApplyDensityPreset(choice.percent)
                }
                DensityChoice.Custom -> densityCustomRow.isVisible = true
            }
        }
    }

    private val densityCustomField = customField()
    private val densityCustomErrorLabel = errorLabel()
    private val densityCustomRow = customDisclosureRow(
        field = densityCustomField,
        unit = "dpi",
        errorLabel = densityCustomErrorLabel,
        onApply = ::applyDensityCustomFromField,
    )

    private val densityHelpLabel = helpLabel("")

    private val densityOverrideNoteLabel = overrideNoteLabel("")
    private val densityResetLink = linkButton("Reset to physical") { onResetDensity() }
    private val densityOverrideRow = overrideRow(densityOverrideNoteLabel, densityResetLink)

    private val densitySection = section(densityHeader, densityChipRow, densityCustomRow, densityHelpLabel, densityOverrideRow)

    // ---- Quick toggles section (`design/README.md` §5.3; tile redesign 2026-09-30) ----

    private val darkThemeToggle = ToggleSwitch(compact = true).apply {
        toolTipText = "cmd uimode night yes|no"
        addActionListener { onSetDarkTheme(isSelected) }
    }
    private val darkThemeValueLabel = toggleValueLabel()
    private val darkThemeTile = QuickToggleTile("Dark theme", darkThemeToggle, darkThemeValueLabel)

    private val showTouchesToggle = ToggleSwitch(compact = true).apply {
        toolTipText = "Useful while recording"
        addActionListener { onSetShowTouches(isSelected) }
    }
    private val showTouchesValueLabel = toggleValueLabel()
    private val showTouchesTile = QuickToggleTile("Show touches", showTouchesToggle, showTouchesValueLabel)

    private val talkBackToggle = ToggleSwitch(compact = true).apply {
        toolTipText = "Samsung or Google TalkBack, detected per device; custom commands in Settings"
        addActionListener { onSetTalkBack(isSelected) }
    }
    private val talkBackValueLabel = toggleValueLabel()
    private val talkBackTile = QuickToggleTile("TalkBack", talkBackToggle, talkBackValueLabel)

    private val animationsOffToggle = ToggleSwitch(compact = true).apply {
        toolTipText = "Sets window, transition and animator scales to 0"
        addActionListener { onSetAnimationsOff(isSelected) }
    }
    private val animationsValueLabel = toggleValueLabel()
    private val animationsTile = QuickToggleTile("Animations off", animationsOffToggle, animationsValueLabel)

    // Developer-options switches (user request, 2026-09-29), rendered from DeveloperOptionsViewState.
    private val developerToggles: Map<DeveloperToggle, ToggleSwitch> = DeveloperToggle.entries.associateWith { toggle ->
        ToggleSwitch(compact = true).apply {
            toolTipText = developerTooltip(toggle)
            addActionListener { onSetDeveloperToggle(toggle, isSelected) }
        }
    }
    private val developerValueLabels: Map<DeveloperToggle, JBLabel> =
        DeveloperToggle.entries.associateWith { toggleValueLabel() }
    private val developerTiles = DeveloperToggle.entries.map { toggle ->
        QuickToggleTile(developerLabel(toggle), developerToggles.getValue(toggle), developerValueLabels.getValue(toggle))
    }

    // Task 059/064 switches (design §5.3 extended grid), rendered from DeviceSettingTogglesViewState.
    private val settingToggles: Map<DeviceSettingToggle, ToggleSwitch> = DeviceSettingToggle.entries.associateWith { toggle ->
        ToggleSwitch(compact = true).apply {
            toolTipText = settingTooltip(toggle)
            addActionListener { onSetDeviceSettingToggle(toggle, isSelected) }
        }
    }
    private val settingValueLabels: Map<DeviceSettingToggle, JBLabel> = DeviceSettingToggle.entries.associateWith { toggleValueLabel() }
    private val settingTiles: Map<DeviceSettingToggle, QuickToggleTile> = DeviceSettingToggle.entries.associateWith { toggle ->
        QuickToggleTile(settingLabel(toggle), settingToggles.getValue(toggle), settingValueLabels.getValue(toggle))
    }

    /** Toggles that are n/a on this device: they stay visible (45%) but do not count in "N of M on". */
    private val unavailableToggles = mutableSetOf<ToggleSwitch>()

    /** Airplane / Wi-Fi are blocked while adb itself runs over Wi-Fi (design §5.3). */
    private var connectedOverWifi = false
    private var lastSettingState = DeviceSettingTogglesViewState()

    private val allToggles: List<ToggleSwitch> =
        listOf(darkThemeToggle, animationsOffToggle, showTouchesToggle, talkBackToggle) + developerToggles.values + settingToggles.values

    private val rotationValueLabel = toggleValueLabel()
    private val rotationRow = labeledValueRow(groupLabel("Rotation"), rotationValueLabel)
    private val rotationChipRow: PresetChipRow<ScreenRotation> = PresetChipRow(
        choices = ScreenRotation.entries.map { rotation ->
            PresetChipChoice(
                value = rotation,
                label = rotation.name,
                isDefault = rotation == ScreenRotation.Auto,
                segmentWeight = 1,
                monospaced = false,
            )
        },
        selected = ScreenRotation.Auto,
        style = PresetChipRowStyle.SEGMENTED,
    ).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        chips.forEach { chip ->
            chip.toolTipText = if (chip.value == ScreenRotation.Auto) {
                "settings put system accelerometer_rotation 1"
            } else {
                "Turns auto-rotate off and sets user_rotation ${if (chip.value == ScreenRotation.Portrait) 0 else 1}"
            }
            chip.isEnabled = false
        }
        onSelectionChanged = { rotation -> onSetRotation(rotation) }
    }

    private val togglesMetaLabel = sectionMetaLabel()

    private val processLimitValueLabel = toggleValueLabel()
    private val processLimitRow = labeledValueRow(groupLabel("Background process limit"), processLimitValueLabel)
    private val processLimitChipRow: PresetChipRow<Int> = PresetChipRow(
        choices = BackgroundProcessLimit.PRESETS.map { limit ->
            PresetChipChoice(
                value = limit,
                label = if (limit == BackgroundProcessLimit.STANDARD) "Standard" else limit.toString(),
                isDefault = limit == BackgroundProcessLimit.STANDARD,
            )
        },
        selected = BackgroundProcessLimit.STANDARD,
        style = PresetChipRowStyle.SEGMENTED,
    ).apply {
        border = JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.sectionInset)
        chips.forEach { chip -> chip.toolTipText = BackgroundProcessLimit.label(chip.value) }
        onSelectionChanged = { limit -> onSetProcessLimit(limit) }
    }

    private val togglesSection = section(
        sectionHeader(sectionTitleLabel("Quick toggles"), togglesMetaLabel),
        toggleGroup(
            "Appearance & accessibility",
            listOf(darkThemeTile, animationsTile, showTouchesTile, talkBackTile) +
                listOf(DeviceSettingToggle.BoldText, DeviceSettingToggle.InvertColors).map(settingTiles::getValue),
        ),
        toggleGroup(
            "Developer",
            developerTiles + listOf(
                DeviceSettingToggle.ShowLayoutBounds,
                DeviceSettingToggle.GpuOverdraw,
                DeviceSettingToggle.GpuProfileBars,
                DeviceSettingToggle.PointerLocation,
            ).map(settingTiles::getValue),
        ),
        toggleGroup(
            "Connectivity",
            listOf(DeviceSettingToggle.AirplaneMode, DeviceSettingToggle.Wifi, DeviceSettingToggle.MobileData).map(settingTiles::getValue),
        ),
        rotationRow,
        rotationChipRow,
        processLimitRow,
        processLimitChipRow,
    )

    /** Language & region (design §3b) and Location (§3c) mount here, after Display scale. */
    val localeSlot: JPanel = sectionSlot()
    val locationSlot: JPanel = sectionSlot()

    private val contentPanel = ViewportWidthPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AdbToolboxTheme.Colors.bg
        add(fontSection)
        add(densitySection)
        add(localeSlot)
        add(locationSlot)
        add(togglesSection)
    }

    private val scrollPane = JScrollPane(contentPanel).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = AdbToolboxTheme.Spacing.s5
    }

    init {
        background = AdbToolboxTheme.Colors.bg
        if (embedded) {
            isOpaque = false
            add(contentPanel, BorderLayout.CENTER)
        } else {
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    // ---- Test-only visibility hooks ----
    internal val fontChipRowForTest: PresetChipRow<*> get() = fontChipRow
    internal val fontCustomFieldForTest: JTextField get() = fontCustomField
    internal val fontCustomRowForTest: JPanel get() = fontCustomRow
    internal val fontCustomErrorLabelForTest: JBLabel get() = fontCustomErrorLabel
    internal val fontResetButtonForTest: JButton get() = fontResetLink
    internal val fontOverrideRowForTest: JPanel get() = fontOverrideRow
    internal val fontMetaLabelForTest: JBLabel get() = fontMetaLabel

    internal val densityChipRowForTest: PresetChipRow<*> get() = densityChipRow
    internal val densityCustomFieldForTest: JTextField get() = densityCustomField
    internal val densityCustomRowForTest: JPanel get() = densityCustomRow
    internal val densityResetButtonForTest: JButton get() = densityResetLink
    internal val densityOverrideRowForTest: JPanel get() = densityOverrideRow
    internal val densityMetaLabelForTest: JBLabel get() = densityMetaLabel
    internal val densityHelpLabelForTest: javax.swing.text.JTextComponent get() = densityHelpLabel

    internal val darkThemeToggleForTest: JToggleButton get() = darkThemeToggle
    internal val showTouchesToggleForTest: JToggleButton get() = showTouchesToggle
    internal val animationsOffToggleForTest: JToggleButton get() = animationsOffToggle
    internal val darkThemeValueLabelForTest: JBLabel get() = darkThemeValueLabel
    internal val showTouchesValueLabelForTest: JBLabel get() = showTouchesValueLabel
    internal val animationsValueLabelForTest: JBLabel get() = animationsValueLabel
    internal val talkBackToggleForTest: JToggleButton get() = talkBackToggle
    internal val talkBackValueLabelForTest: JBLabel get() = talkBackValueLabel
    internal fun developerToggleForTest(toggle: DeveloperToggle): JToggleButton = developerToggles.getValue(toggle)
    internal fun developerValueLabelForTest(toggle: DeveloperToggle): JBLabel = developerValueLabels.getValue(toggle)
    internal val processLimitChipRowForTest: PresetChipRow<Int> get() = processLimitChipRow
    internal val processLimitValueLabelForTest: JBLabel get() = processLimitValueLabel
    internal val togglesMetaLabelForTest: JBLabel get() = togglesMetaLabel
    internal val darkThemeTileForTest: QuickToggleTile get() = darkThemeTile
    internal fun settingToggleForTest(toggle: DeviceSettingToggle): JToggleButton = settingToggles.getValue(toggle)
    internal fun settingValueLabelForTest(toggle: DeviceSettingToggle): JBLabel = settingValueLabels.getValue(toggle)
    internal val rotationChipRowForTest: PresetChipRow<ScreenRotation> get() = rotationChipRow
    internal val rotationValueLabelForTest: JBLabel get() = rotationValueLabel

    fun update(state: FontScaleState) {
        val current = when (state) {
            FontScaleState.Loading -> null
            is FontScaleState.Idle -> state.current
            is FontScaleState.Applying -> state.current
            is FontScaleState.Error -> state.current
        }
        val overridden = current != null && current != FontScalePresets.DEFAULT
        fontMetaLabel.text = when {
            current == null -> "—"
            overridden -> "${formatFontScale(current)}× applied"
            else -> "default"
        }
        fontMetaLabel.foreground = if (overridden) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint
        if (current != null) {
            val selection = if (current in FontScalePresets.VALUES) FontChoice.Preset(current) else FontChoice.Custom
            fontChipRow.setSelectedValue(selection)
        }
        fontChipRow.applyPending = state is FontScaleState.Applying
        fontOverrideRow.isVisible = overridden
        if (state is FontScaleState.Error) {
            fontCustomErrorLabel.text = state.message
            fontCustomErrorLabel.isVisible = true
            fontCustomRow.isVisible = true
        }
    }

    fun update(state: DensityViewState) {
        val reading = when (state) {
            DensityViewState.Loading -> null
            is DensityViewState.Idle -> state.reading
            is DensityViewState.Applying -> state.reading
            is DensityViewState.Error -> state.reading
        }
        val overridden = reading?.overrideDpi != null
        densityMetaLabel.text = when {
            reading == null -> "—"
            overridden -> "${reading.overrideDpi} dpi"
            else -> "${reading.physicalDpi} dpi"
        }
        densityMetaLabel.foreground = if (overridden) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint
        if (reading != null) {
            densityChipRow.chips.forEach { chip ->
                val value = chip.value
                chip.toolTipText = when (value) {
                    is DensityChoice.Preset -> if (value.percent == 100) {
                        "Physical density — ${reading.physicalDpi} dpi"
                    } else {
                        "${percentToDpi(reading.physicalDpi, value.percent)} dpi"
                    }
                    DensityChoice.Custom -> "Enter an absolute dpi value"
                }
            }
            densityHelpLabel.text =
                "Percentages are relative to the physical density (${reading.physicalDpi} dpi). " +
                    "Values outside ${DensityPresets.SAFE_RANGE_MIN_PERCENT}–${DensityPresets.SAFE_RANGE_MAX_PERCENT}% can make the UI unusable."
            densityOverrideNoteLabel.text = "Physical density is ${reading.physicalDpi} dpi"
            val selection = if (reading.overrideDpi == null) {
                DensityChoice.Preset(100)
            } else {
                DensityPresets.PERCENTAGES
                    .firstOrNull { percentToDpi(reading.physicalDpi, it) == reading.overrideDpi }
                    ?.let { DensityChoice.Preset(it) }
                    ?: DensityChoice.Custom
            }
            densityChipRow.setSelectedValue(selection)
        }
        densityChipRow.applyPending = state is DensityViewState.Applying
        densityOverrideRow.isVisible = overridden
        if (state is DensityViewState.Error) {
            densityCustomErrorLabel.text = state.message
            densityCustomErrorLabel.isVisible = true
            densityCustomRow.isVisible = true
        }
    }

    fun update(state: QuickTogglesViewState) {
        applyToggleState(darkThemeToggle, darkThemeValueLabel, state.darkTheme, toText = { if (it) "night yes" else "night no" })
        applyToggleState(showTouchesToggle, showTouchesValueLabel, state.showTouches, toText = { if (it) "on" else "off" })
        val talkBackSource = when (state.talkBackProfile) {
            TalkBackProfile.Samsung -> " · Samsung"
            TalkBackProfile.Google -> " · Google"
            TalkBackProfile.Custom -> " · custom"
            null -> ""
        }
        applyToggleState(talkBackToggle, talkBackValueLabel, state.talkBack, toText = { (if (it) "on" else "off") + talkBackSource })
        applyToggleState(
            animationsOffToggle,
            animationsValueLabel,
            state.animations,
            toSelected = { it != AnimationsSummary.AllOn },
            toText = { summary ->
                when (summary) {
                    AnimationsSummary.AllOn -> "scale 1×"
                    AnimationsSummary.AllOff -> "scale 0×"
                    is AnimationsSummary.Mixed -> "mixed"
                }
            },
        )
        refreshTogglesMeta()
    }

    /** The row label of [toggle], as shown to the user. */
    fun developerLabelFor(toggle: DeveloperToggle): String = developerLabel(toggle)

    fun update(state: DeveloperOptionsViewState) {
        DeveloperToggle.entries.forEach { toggle ->
            val field = state.toggles[toggle] ?: QuickToggleFieldState.Loading
            val valueLabel = developerValueLabels.getValue(toggle)
            applyToggleState(developerToggles.getValue(toggle), valueLabel, field, toText = { if (it) "on" else "off" })
            val switch = developerToggles.getValue(toggle)
            if (field is QuickToggleFieldState.Error) {
                if (field.lastKnown == null) valueLabel.text = "n/a"
                valueLabel.toolTipText = field.message
            } else {
                valueLabel.toolTipText = null
            }
            if (field is QuickToggleFieldState.Error && field.lastKnown == null) unavailableToggles += switch else unavailableToggles -= switch
        }

        val limitField = state.processLimit
        val limit = limitField.valueOrNull()
        processLimitValueLabel.text = when {
            limit == null -> if (limitField is QuickToggleFieldState.Error) "n/a" else "—"
            limit < 0 -> "standard"
            else -> "$limit max"
        }
        processLimitValueLabel.foreground =
            if (limit != null && limit >= 0) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint
        processLimitValueLabel.toolTipText = (limitField as? QuickToggleFieldState.Error)?.message
        if (limit != null && limit in BackgroundProcessLimit.PRESETS) processLimitChipRow.setSelectedValue(limit)
        processLimitChipRow.applyPending = limitField is QuickToggleFieldState.Applying
        processLimitChipRow.chips.forEach { it.isEnabled = limitField !is QuickToggleFieldState.Loading }
        refreshTogglesMeta()
    }

    /** The label of [toggle], as shown to the user. */
    fun settingLabelFor(toggle: DeviceSettingToggle): String = settingLabel(toggle)

    fun setConnectedOverWifi(overWifi: Boolean) {
        if (overWifi == connectedOverWifi) return
        connectedOverWifi = overWifi
        update(lastSettingState)
    }

    fun update(state: DeviceSettingTogglesViewState) {
        lastSettingState = state
        DeviceSettingToggle.entries.forEach { toggle ->
            val field = state.toggles[toggle] ?: QuickToggleFieldState.Loading
            val switch = settingToggles.getValue(toggle)
            val valueLabel = settingValueLabels.getValue(toggle)
            applyToggleState(switch, valueLabel, field, toText = { on -> settingValue(toggle, on) })
            val unavailable = field is QuickToggleFieldState.Error && field.lastKnown == null
            val blocked = connectedOverWifi && (toggle == DeviceSettingToggle.AirplaneMode || toggle == DeviceSettingToggle.Wifi)
            when {
                unavailable -> {
                    valueLabel.text = "n/a"
                    switch.isEnabled = false
                    switch.toolTipText = unavailableReason(toggle, (field as QuickToggleFieldState.Error).message)
                    unavailableToggles += switch
                }
                blocked -> {
                    valueLabel.text = "adb over Wi-Fi"
                    switch.isEnabled = false
                    switch.toolTipText = "Connected over Wi-Fi — ${if (toggle == DeviceSettingToggle.Wifi) "turning Wi-Fi off" else "airplane mode"} " +
                        "would drop this adb session. Connect over USB to use it."
                    unavailableToggles -= switch
                }
                else -> {
                    switch.toolTipText = settingTooltip(toggle)
                    unavailableToggles -= switch
                }
            }
            valueLabel.toolTipText = switch.toolTipText.takeIf { unavailable || blocked }
            settingTiles.getValue(toggle).isEnabled = !unavailable && !blocked
        }
        val rotationField = state.rotation
        val rotation = rotationField.valueOrNull()
        rotationValueLabel.text = when (rotation) {
            null -> if (rotationField is QuickToggleFieldState.Error) "n/a" else "—"
            ScreenRotation.Auto -> "auto-rotate"
            else -> "${rotation.name.lowercase()} · locked"
        }
        rotationValueLabel.foreground =
            if (rotation != null && rotation != ScreenRotation.Auto) AdbToolboxTheme.Colors.amber else AdbToolboxTheme.Colors.textFaint
        rotationValueLabel.toolTipText = (rotationField as? QuickToggleFieldState.Error)?.message
        if (rotation != null) rotationChipRow.setSelectedValue(rotation)
        rotationChipRow.applyPending = rotationField is QuickToggleFieldState.Applying
        rotationChipRow.chips.forEach { it.isEnabled = rotationField !is QuickToggleFieldState.Loading }
        refreshTogglesMeta()
    }

    /** Header meta "N of M on", from device readbacks only; M leaves out n/a toggles; "—" until any toggle has loaded. */
    private fun refreshTogglesMeta() {
        val counted = allToggles - unavailableToggles
        togglesMetaLabel.text = if (allToggles.none { it.isEnabled }) {
            "—"
        } else {
            "${counted.count { it.isSelected }} of ${counted.size} on"
        }
    }

    private fun <T> applyToggleState(
        toggle: JToggleButton,
        valueLabel: JBLabel,
        field: QuickToggleFieldState<T>,
        toSelected: (T) -> Boolean = { it as Boolean },
        toText: (T) -> String = { "" },
    ) {
        when (field) {
            QuickToggleFieldState.Loading -> toggle.isEnabled = false
            is QuickToggleFieldState.Idle -> {
                toggle.isEnabled = true
                toggle.isSelected = toSelected(field.value)
            }
            is QuickToggleFieldState.Applying -> {
                toggle.isEnabled = false
                toggle.isSelected = toSelected(field.optimistic)
            }
            is QuickToggleFieldState.Error -> {
                toggle.isEnabled = true
                field.lastKnown?.let { toggle.isSelected = toSelected(it) }
            }
        }
        valueLabel.text = valueText(field, toText)
    }

    private fun <T> valueText(field: QuickToggleFieldState<T>, toText: (T) -> String): String = when (field) {
        QuickToggleFieldState.Loading -> "—"
        is QuickToggleFieldState.Idle -> toText(field.value)
        is QuickToggleFieldState.Applying -> toText(field.optimistic)
        is QuickToggleFieldState.Error -> field.lastKnown?.let(toText) ?: "—"
    }

    private fun applyFontCustomFromField() {
        val parsed = fontCustomField.text.trim().toDoubleOrNull()
        if (parsed == null) {
            fontCustomErrorLabel.text = "Invalid value"
            fontCustomErrorLabel.isVisible = true
        } else {
            fontCustomErrorLabel.isVisible = false
            onApplyFontScale(parsed)
        }
    }

    private fun applyDensityCustomFromField() {
        val parsed = densityCustomField.text.trim().toIntOrNull()
        if (parsed == null) {
            densityCustomErrorLabel.text = "Invalid value"
            densityCustomErrorLabel.isVisible = true
        } else {
            densityCustomErrorLabel.isVisible = false
            onApplyCustomDensity(parsed)
        }
    }

    private companion object {
        fun sectionTitleLabel(text: String) = DesignSections.titleLabel(text)

        fun sectionMetaLabel() = DesignSections.metaLabel()

        fun sectionHeader(title: JBLabel, meta: JBLabel?) = DesignSections.header(title, meta)

        fun customField() = JBTextField().apply {
            font = AdbToolboxTheme.Typography.mono
            background = AdbToolboxTheme.Colors.field
            preferredSize = Dimension(JBUI.scale(64), AdbToolboxTheme.Sizes.field)
            border = BorderFactory.createCompoundBorder(
                SolidChipBorder(AdbToolboxTheme.Colors.borderStrong, radius = { AdbToolboxTheme.Radii.field }),
                JBUI.Borders.empty(0, AdbToolboxTheme.Spacing.s3),
            )
        }

        fun sectionSlot() = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }

        fun errorLabel() = JBLabel("").apply {
            font = AdbToolboxTheme.Typography.caption.deriveFont(JBUI.scale(10.5f))
            foreground = AdbToolboxTheme.Colors.red
            isVisible = false
        }

        // `customRowStyle`: field · unit · secondary Apply · inline error, gap 6, `padding: 2px 10px 0`.
        fun customDisclosureRow(field: JBTextField, unit: String, errorLabel: JBLabel, onApply: () -> Unit): JPanel {
            field.addActionListener { onApply() }
            val unitLabel = JBLabel(unit).apply {
                font = AdbToolboxTheme.Typography.mono
                foreground = AdbToolboxTheme.Colors.textFaint
            }
            val applyButton = DesignButton("Apply", DesignButtonStyle.SECONDARY).apply {
                addActionListener { onApply() }
            }
            return flexRow(AdbToolboxTheme.Spacing.s3, field, unitLabel, applyButton, errorLabel).apply {
                isVisible = false
                border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
            }
        }

        fun overrideNoteLabel(text: String) = JBLabel(text).apply {
            font = AdbToolboxTheme.Typography.caption.deriveFont(JBUI.scale(10.5f))
            foreground = AdbToolboxTheme.Colors.textFaint
        }

        fun linkButton(text: String, onClick: () -> Unit) = DesignButton(text, DesignButtonStyle.LINK).apply {
            addActionListener { onClick() }
        }

        // `resetRowStyle`: note (`margin-right: auto`) and link, gap 8, `padding: 2px 10px 0`.
        fun overrideRow(note: JBLabel, link: JButton) = flexRow(AdbToolboxTheme.Spacing.s4, note, link, fill = note).apply {
            isVisible = false
            border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
        }

        fun helpLabel(text: String) = DesignSections.helpText(text)

        fun section(vararg children: java.awt.Component) = DesignSections.section(*children)

        fun toggleValueLabel() = JBLabel("").apply {
            font = AdbToolboxTheme.Typography.mono.deriveFont(JBUI.scale(10f))
            foreground = AdbToolboxTheme.Colors.textFaint
        }

        // Group caption: 9.5 bold uppercase `textFaint`; the accessible name keeps the readable casing.
        fun groupLabel(text: String) = JBLabel(text.uppercase()).apply {
            font = AdbToolboxTheme.Typography.groupLabel
            foreground = AdbToolboxTheme.Colors.textFaint
            getAccessibleContext().accessibleName = text
        }

        // `groupWrap`: caption above a tile grid, 5px apart.
        internal fun toggleGroup(label: String, tiles: List<QuickToggleTile>): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(DesignSections.inset(groupLabel(label)))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(5)))
            add(QuickToggleGrid(tiles))
        }

        // Caption · spacer · mono value.
        fun labeledValueRow(caption: JBLabel, valueLabel: JBLabel): JPanel {
            val spacer = flexSpacer()
            return flexRow(AdbToolboxTheme.Spacing.s4, caption, spacer, valueLabel, fill = spacer).apply {
                border = JBUI.Borders.empty(AdbToolboxTheme.Spacing.s1, AdbToolboxTheme.Spacing.sectionInset, 0, AdbToolboxTheme.Spacing.sectionInset)
            }
        }

        fun developerLabel(toggle: DeveloperToggle): String = when (toggle) {
            DeveloperToggle.StayAwake -> "Stay awake"
            DeveloperToggle.DontKeepActivities -> "Don't keep activities"
            DeveloperToggle.ShowViewUpdates -> "Show view updates"
            DeveloperToggle.ShowSurfaceUpdates -> "Show surface updates"
        }

        fun developerTooltip(toggle: DeveloperToggle): String = when (toggle) {
            DeveloperToggle.StayAwake -> "Screen never sleeps while charging"
            DeveloperToggle.DontKeepActivities -> "Destroy every activity as soon as the user leaves it"
            DeveloperToggle.ShowViewUpdates -> "Flash views inside windows when they redraw"
            DeveloperToggle.ShowSurfaceUpdates -> "Flash entire window surfaces when they update — needs adb root on most devices"
        }

        fun settingLabel(toggle: DeviceSettingToggle): String = when (toggle) {
            DeviceSettingToggle.ShowLayoutBounds -> "Show layout bounds"
            DeviceSettingToggle.GpuOverdraw -> "GPU overdraw"
            DeviceSettingToggle.GpuProfileBars -> "GPU profile bars"
            DeviceSettingToggle.PointerLocation -> "Pointer location"
            DeviceSettingToggle.BoldText -> "Bold text"
            DeviceSettingToggle.InvertColors -> "Invert colors"
            DeviceSettingToggle.AirplaneMode -> "Airplane mode"
            DeviceSettingToggle.Wifi -> "Wi-Fi"
            DeviceSettingToggle.MobileData -> "Mobile data"
        }

        fun settingTooltip(toggle: DeviceSettingToggle): String = when (toggle) {
            DeviceSettingToggle.ShowLayoutBounds -> "setprop debug.layout true|false — open apps redraw with clip bounds and margins"
            DeviceSettingToggle.GpuOverdraw -> "setprop debug.hwui.overdraw show|false — tints pixels drawn more than once"
            DeviceSettingToggle.GpuProfileBars -> "setprop debug.hwui.profile visual_bars|false — frame-time bars at the bottom of the screen"
            DeviceSettingToggle.PointerLocation -> "settings put system pointer_location 1|0 — draws touch coordinates on top"
            DeviceSettingToggle.BoldText -> "settings put secure font_weight_adjustment 300|0"
            DeviceSettingToggle.InvertColors -> "settings put secure accessibility_display_inversion_enabled 1|0"
            DeviceSettingToggle.AirplaneMode -> "cmd connectivity airplane-mode enable|disable"
            DeviceSettingToggle.Wifi -> "svc wifi enable|disable — turning off Wi-Fi can drop a wireless adb connection"
            DeviceSettingToggle.MobileData -> "svc data enable|disable"
        }

        fun settingValue(toggle: DeviceSettingToggle, on: Boolean): String = when {
            !on -> "off"
            toggle == DeviceSettingToggle.BoldText -> "weight +300"
            toggle == DeviceSettingToggle.GpuOverdraw -> "show"
            toggle == DeviceSettingToggle.GpuProfileBars -> "bars"
            else -> "on"
        }

        fun unavailableReason(toggle: DeviceSettingToggle, message: String): String = when (toggle) {
            DeviceSettingToggle.MobileData -> "No cellular radio on this device"
            DeviceSettingToggle.BoldText -> "Needs Android 12 (API 31)"
            else -> message
        }

        fun fontChipLabel(value: Double): String {
            val text = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            return "$text×"
        }
    }
}
