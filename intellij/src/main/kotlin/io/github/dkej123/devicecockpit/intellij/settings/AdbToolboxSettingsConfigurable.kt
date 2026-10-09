package io.github.dkej123.devicecockpit.intellij.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.dkej123.devicecockpit.application.settings.SettingsApplyResult
import io.github.dkej123.devicecockpit.application.settings.SettingsUseCase
import io.github.dkej123.devicecockpit.domain.settings.SettingsFieldError
import io.github.dkej123.devicecockpit.domain.settings.SettingsState
import io.github.dkej123.devicecockpit.intellij.composition.AdbToolboxProjectService
import io.github.dkej123.devicecockpit.intellij.diagnostics.DiagnosticsActions
import io.github.dkej123.devicecockpit.intellij.diagnostics.DiagnosticsService
import io.github.dkej123.devicecockpit.intellij.persistence.AdbToolboxProjectState
import io.github.dkej123.devicecockpit.intellij.persistence.SettingsPersistenceAdapter
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import kotlinx.coroutines.runBlocking
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** The application-wide diagnostics preference edited on this page. */
internal interface DiagnosticsPreferences {
    var verbose: Boolean
}

/** Application-wide preferences edited on this page (design §11): clipboard and MCP access. */
internal interface AppPreferences {
    var copyScreenshotsToClipboard: Boolean
    var mcpAccess: io.github.dkej123.devicecockpit.application.mcp.McpAccess

    /** Starts/stops the MCP server for [mcpAccess], registering [project]'s tools first. */
    fun applyMcp(project: Project?)
}

private object ServiceAppPreferences : AppPreferences {
    private val settings get() = io.github.dkej123.devicecockpit.intellij.mcp.AdbToolboxAppSettings.getInstance()
    override var copyScreenshotsToClipboard: Boolean
        get() = settings.copyScreenshotsToClipboard
        set(value) {
            settings.copyScreenshotsToClipboard = value
        }
    override var mcpAccess: io.github.dkej123.devicecockpit.application.mcp.McpAccess
        get() = settings.mcpAccess
        set(value) {
            settings.mcpAccess = value
        }

    override fun applyMcp(project: Project?) {
        if (project != null && mcpAccess != io.github.dkej123.devicecockpit.application.mcp.McpAccess.Off) {
            project.service<AdbToolboxProjectService>().registerMcpTools()
        }
        io.github.dkej123.devicecockpit.intellij.mcp.McpServerService.getInstance().applyAccess()
    }
}

private object ServiceDiagnosticsPreferences : DiagnosticsPreferences {
    override var verbose: Boolean
        get() = DiagnosticsService.getInstance().verbose
        set(value) {
            DiagnosticsService.getInstance().verbose = value
        }
}

/** Native, project-scoped editor for the persisted task 038 settings, plus the diagnostics tools. */
class AdbToolboxSettingsConfigurable internal constructor(
    private val backend: SettingsEditorBackend,
    private val project: Project? = null,
    private val diagnosticsPreferences: DiagnosticsPreferences = ServiceDiagnosticsPreferences,
    private val appPreferences: AppPreferences = ServiceAppPreferences,
) : Configurable {

    constructor(project: Project) : this(ProjectSettingsEditorBackend(project), project)

    private var form: SettingsForm? = null
    private var persisted: SettingsState? = null

    override fun getDisplayName(): String = "Device Cockpit"

    override fun createComponent(): JComponent = form?.panel ?: SettingsForm(project).also {
        form = it
        reset()
    }.panel

    override fun isModified(): Boolean {
        val currentForm = form ?: return false
        val baseline = persisted ?: return false
        return currentForm.adbPath.text != baseline.adbPathOverride.orEmpty() ||
            currentForm.scrcpyPath.text != baseline.scrcpyPathOverride.orEmpty() ||
            currentForm.captureDirectory.text != baseline.captureDirectory.orEmpty() ||
            currentForm.logcatBufferSizeKb.text != baseline.logcatBufferSizeKb.toString() ||
            currentForm.talkBackOn.text != baseline.talkBackOnCommand.orEmpty() ||
            currentForm.talkBackOff.text != baseline.talkBackOffCommand.orEmpty() ||
            currentForm.verboseDiagnostics.isSelected != diagnosticsPreferences.verbose ||
            currentForm.copyToClipboard.isSelected != appPreferences.copyScreenshotsToClipboard ||
            currentForm.mcp.access != appPreferences.mcpAccess
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val currentForm = form ?: return
        val bufferSize = currentForm.logcatBufferSizeKb.text.trim().toIntOrNull()
            ?: throw ConfigurationException("Logcat buffer size must be a whole number of kilobytes.")
        val candidate = SettingsState(
            adbPathOverride = currentForm.adbPath.text,
            scrcpyPathOverride = currentForm.scrcpyPath.text,
            captureDirectory = currentForm.captureDirectory.text,
            logcatBufferSizeKb = bufferSize,
            talkBackOnCommand = currentForm.talkBackOn.text,
            talkBackOffCommand = currentForm.talkBackOff.text,
        )

        diagnosticsPreferences.verbose = currentForm.verboseDiagnostics.isSelected
        appPreferences.copyScreenshotsToClipboard = currentForm.copyToClipboard.isSelected
        if (currentForm.mcp.access != appPreferences.mcpAccess) {
            appPreferences.mcpAccess = currentForm.mcp.access
            appPreferences.applyMcp(project)
        }
        when (val result = backend.apply(candidate)) {
            is SettingsApplyResult.Applied -> {
                persisted = result.state
                writeToForm(currentForm, result.state)
            }
            is SettingsApplyResult.Invalid -> throw ConfigurationException(result.errors.toMessage())
        }
    }

    override fun reset() {
        form?.let { currentForm ->
            backend.read().also { loaded ->
                persisted = loaded
                writeToForm(currentForm, loaded)
            }
        }
    }

    override fun disposeUIResources() {
        form = null
        persisted = null
        backend.dispose()
    }

    private fun writeToForm(target: SettingsForm, state: SettingsState) {
        target.adbPath.text = state.adbPathOverride.orEmpty()
        target.scrcpyPath.text = state.scrcpyPathOverride.orEmpty()
        target.captureDirectory.text = state.captureDirectory.orEmpty()
        target.logcatBufferSizeKb.text = state.logcatBufferSizeKb.toString()
        target.talkBackOn.text = state.talkBackOnCommand.orEmpty()
        target.talkBackOff.text = state.talkBackOffCommand.orEmpty()
        target.verboseDiagnostics.isSelected = diagnosticsPreferences.verbose
        target.copyToClipboard.isSelected = appPreferences.copyScreenshotsToClipboard
        target.mcp.access = appPreferences.mcpAccess
    }

    private class SettingsForm(project: Project?) {
        val copyToClipboard = JBCheckBox("Also copy screenshots to the clipboard").apply { name = "copyScreenshotsCheckBox" }
        val mcp = io.github.dkej123.devicecockpit.intellij.mcp.McpSettingsPanel(
            status = { io.github.dkej123.devicecockpit.intellij.mcp.McpServerService.getInstance().status.value },
            token = { io.github.dkej123.devicecockpit.intellij.mcp.AdbToolboxAppSettings.getInstance().mcpToken() },
            regenerate = { io.github.dkej123.devicecockpit.intellij.mcp.McpServerService.getInstance().regenerateToken() },
            tools = io.github.dkej123.devicecockpit.application.mcp.tools.mcpCatalog(),
        )
        val verboseDiagnostics = JBCheckBox("Verbose diagnostics (debug level)").apply {
            name = "verboseDiagnosticsCheckBox"
        }
        private val diagnosticsButtons = JPanel(FlowLayout(FlowLayout.LEADING, com.intellij.ui.scale.JBUIScale.scale(6), 0)).apply {
            add(JButton("Collect Diagnostics…").apply { addActionListener { DiagnosticsActions.collect(project) } })
            add(JButton("Record Performance (60 s)").apply { addActionListener { DiagnosticsActions.recordPerformance(project) } })
            add(JButton("Open Log Folder").apply { addActionListener { DiagnosticsActions.openLogFolder() } })
        }

        val adbPath = settingsField("adbPathField")
        val scrcpyPath = settingsField("scrcpyPathField")
        val captureDirectory = settingsField("captureDirectoryField")
        val logcatBufferSizeKb = settingsField("logcatBufferSizeKbField")
        val talkBackOn = settingsField("talkBackOnCommandField").apply {
            emptyText.text = "Auto: Samsung or Google TalkBack, detected per device"
        }
        val talkBackOff = settingsField("talkBackOffCommandField").apply {
            emptyText.text = "Auto: clears the accessibility services"
        }

        val panel: JPanel = FormBuilder.createFormBuilder()
            .addComponent(com.intellij.ui.TitledSeparator("Paths"))
            .addLabeledComponent("ADB executable:", adbPath, 1, false)
            .addTooltip("Leave blank to use automatic tool discovery.")
            .addLabeledComponent("scrcpy executable:", scrcpyPath, 1, false)
            .addTooltip("Leave blank to use automatic tool discovery.")
            .addComponent(com.intellij.ui.TitledSeparator("Capture"))
            .addLabeledComponent("Save to:", captureDirectory, 1, false)
            .addTooltip("Leave blank to use the default capture location.")
            .addComponent(copyToClipboard)
            .addTooltip("Applies to every screenshot, including full-page captures. The toast says “copied to clipboard” when it happened.")
            .addComponent(com.intellij.ui.TitledSeparator("Logcat"))
            .addLabeledComponent("Logcat buffer size (KB):", logcatBufferSizeKb, 1, false)
            .addTooltip(
                "Allowed range: ${SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB}–" +
                    "${SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB} KB.",
            )
            .addLabeledComponent("TalkBack on command:", talkBackOn, 1, false)
            .addLabeledComponent("TalkBack off command:", talkBackOff, 1, false)
            .addTooltip(
                "Run on the device shell by the Display view's TalkBack toggle; \"adb shell\" prefixes are dropped. " +
                    "Leave blank to use the built-in Samsung or Google command for the connected device.",
            )
            .addComponent(com.intellij.ui.TitledSeparator("AI agents (MCP)"))
            .addComponent(mcp)
            .addComponent(com.intellij.ui.TitledSeparator("Diagnostics"))
            .addComponent(verboseDiagnostics)
            .addTooltip("Also records every successful command, stream chunk and Logcat batch. Leave off unless asked.")
            .addComponent(diagnosticsButtons)
            .addTooltip("Collect Diagnostics saves logs, adb state and IDE freeze dumps to one ZIP to attach to a bug report.")
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    companion object {
        const val ID: String = "dev.acme.adbtoolbox.settings"

        private fun settingsField(componentName: String): JBTextField = JBTextField().apply {
            name = componentName
            columns = 36
            // Paths and the buffer-size value are copy-paste targets, per the design system's
            // "serials, IPs, ports, package names, densities... are always mono" rule (matches the
            // same JBTextField().apply { font = AdbToolboxTheme.Typography.mono } custom-field
            // pattern task 046's DisplayPanel uses for its own numeric override fields).
            font = AdbToolboxTheme.Typography.mono
        }

        private fun Set<SettingsFieldError>.toMessage(): String = map { error ->
            when (error) {
                SettingsFieldError.AdbPathNotExecutable -> "ADB executable path must point to an executable file."
                SettingsFieldError.ScrcpyPathNotExecutable -> "scrcpy executable path must point to an executable file."
                SettingsFieldError.CaptureDirectoryInvalid -> "Capture directory must point to an existing directory."
                SettingsFieldError.LogcatBufferSizeOutOfRange ->
                    "Logcat buffer size must be between ${SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB} and " +
                        "${SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB} KB."
            }
        }.joinToString("\n")
    }
}

internal interface SettingsEditorBackend {
    fun read(): SettingsState
    fun apply(candidate: SettingsState): SettingsApplyResult
    fun dispose()
}

private class ProjectSettingsEditorBackend(
    private val project: Project,
) : SettingsEditorBackend {
    private val persistence = SettingsPersistenceAdapter(project.service<AdbToolboxProjectState>())
    private val settings: SettingsUseCase
        get() = project.service<AdbToolboxProjectService>().settingsUseCase

    // Reading the in-memory PersistentStateComponent slice is intentionally lightweight. In
    // particular it must not initialize the full composition service while IntelliJ's
    // buildSearchableOptions process merely instantiates and indexes this Configurable.
    override fun read(): SettingsState = persistence.readSettingsNow()

    override fun apply(candidate: SettingsState): SettingsApplyResult =
        runModal("Applying Device Cockpit Settings") { settings.apply(candidate) }

    override fun dispose() = Unit

    /** IntelliJ runs this modal computation on a worker thread while keeping the EDT responsive. */
    private fun <T> runModal(title: String, operation: suspend () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<T, RuntimeException> { runBlocking { operation() } },
            title,
            true,
            project,
        )
}
