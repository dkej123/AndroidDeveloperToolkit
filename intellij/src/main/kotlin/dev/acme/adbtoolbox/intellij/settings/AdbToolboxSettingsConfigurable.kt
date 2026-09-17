package dev.acme.adbtoolbox.intellij.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.acme.adbtoolbox.application.settings.SettingsApplyResult
import dev.acme.adbtoolbox.application.settings.SettingsUseCase
import dev.acme.adbtoolbox.domain.settings.SettingsFieldError
import dev.acme.adbtoolbox.domain.settings.SettingsState
import dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService
import dev.acme.adbtoolbox.intellij.persistence.AdbToolboxProjectState
import dev.acme.adbtoolbox.intellij.persistence.SettingsPersistenceAdapter
import dev.acme.adbtoolbox.intellij.ui.common.AdbToolboxTheme
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent
import javax.swing.JPanel

/** Native, project-scoped editor for the persisted task 038 settings. */
class AdbToolboxSettingsConfigurable internal constructor(
    private val backend: SettingsEditorBackend,
) : Configurable {

    constructor(project: Project) : this(ProjectSettingsEditorBackend(project))

    private var form: SettingsForm? = null
    private var persisted: SettingsState? = null

    override fun getDisplayName(): String = "ADB Toolbox"

    override fun createComponent(): JComponent = form?.panel ?: SettingsForm().also {
        form = it
        reset()
    }.panel

    override fun isModified(): Boolean {
        val currentForm = form ?: return false
        val baseline = persisted ?: return false
        return currentForm.adbPath.text != baseline.adbPathOverride.orEmpty() ||
            currentForm.scrcpyPath.text != baseline.scrcpyPathOverride.orEmpty() ||
            currentForm.captureDirectory.text != baseline.captureDirectory.orEmpty() ||
            currentForm.logcatBufferSizeKb.text != baseline.logcatBufferSizeKb.toString()
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
        )

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
    }

    private class SettingsForm {
        val adbPath = settingsField("adbPathField")
        val scrcpyPath = settingsField("scrcpyPathField")
        val captureDirectory = settingsField("captureDirectoryField")
        val logcatBufferSizeKb = settingsField("logcatBufferSizeKbField")

        val panel: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("ADB executable:", adbPath, 1, false)
            .addTooltip("Leave blank to use automatic tool discovery.")
            .addLabeledComponent("scrcpy executable:", scrcpyPath, 1, false)
            .addTooltip("Leave blank to use automatic tool discovery.")
            .addLabeledComponent("Capture directory:", captureDirectory, 1, false)
            .addTooltip("Leave blank to use the default capture location.")
            .addLabeledComponent("Logcat buffer size (KB):", logcatBufferSizeKb, 1, false)
            .addTooltip(
                "Allowed range: ${SettingsState.MIN_LOGCAT_BUFFER_SIZE_KB}–" +
                    "${SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB} KB.",
            )
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
        runModal("Applying ADB Toolbox Settings") { settings.apply(candidate) }

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
