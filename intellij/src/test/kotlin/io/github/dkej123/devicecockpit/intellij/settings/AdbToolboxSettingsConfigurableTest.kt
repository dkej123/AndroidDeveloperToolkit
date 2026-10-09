package io.github.dkej123.devicecockpit.intellij.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.settings.SettingsApplyResult
import io.github.dkej123.devicecockpit.domain.settings.SettingsState
import io.github.dkej123.devicecockpit.domain.settings.SettingsFieldError
import io.github.dkej123.devicecockpit.intellij.ui.common.AdbToolboxTheme
import java.awt.Component
import java.awt.Container
import javax.swing.JTextField

class AdbToolboxSettingsConfigurableTest : BasePlatformTestCase() {

    private class FakeDiagnosticsPreferences(override var verbose: Boolean = false) : DiagnosticsPreferences

    fun `test the verbose diagnostics checkbox reflects, modifies and applies the preference`() {
        val preferences = FakeDiagnosticsPreferences(verbose = false)
        val configurable = AdbToolboxSettingsConfigurable(FakeSettingsEditorBackend(), diagnosticsPreferences = preferences)
        val component = configurable.createComponent()
        val checkbox = descendants(component).filterIsInstance<javax.swing.JCheckBox>().single { it.name == "verboseDiagnosticsCheckBox" }

        assertFalse(checkbox.isSelected)
        checkbox.isSelected = true
        assertTrue(configurable.isModified)

        configurable.apply()

        assertTrue(preferences.verbose)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    private class FakeAppPreferences(
        override var copyScreenshotsToClipboard: Boolean = true,
        override var mcpAccess: io.github.dkej123.devicecockpit.application.mcp.McpAccess = io.github.dkej123.devicecockpit.application.mcp.McpAccess.Off,
    ) : AppPreferences {
        var applied = 0
        override fun applyMcp(project: com.intellij.openapi.project.Project?) {
            applied++
        }
    }

    fun `test the clipboard checkbox and the MCP access level are applied application-wide`() {
        val preferences = FakeAppPreferences()
        val configurable = AdbToolboxSettingsConfigurable(FakeSettingsEditorBackend(), appPreferences = preferences)
        val component = configurable.createComponent()
        val checkbox = descendants(component).filterIsInstance<javax.swing.JCheckBox>().single { it.name == "copyScreenshotsCheckBox" }
        val fullControl = descendants(component).filterIsInstance<javax.swing.JRadioButton>().single { it.text == "Full control" }

        assertTrue(checkbox.isSelected)
        checkbox.isSelected = false
        fullControl.isSelected = true
        assertTrue(configurable.isModified)
        configurable.apply()

        assertFalse(preferences.copyScreenshotsToClipboard)
        assertEquals(io.github.dkej123.devicecockpit.application.mcp.McpAccess.FullControl, preferences.mcpAccess)
        assertEquals(1, preferences.applied)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) + ((component as? Container)?.components?.flatMap(::descendants) ?: emptyList())

    fun `test path and buffer fields use the design system's mono font for copy-paste values`() {
        val configurable = AdbToolboxSettingsConfigurable(FakeSettingsEditorBackend())

        val component = configurable.createComponent()

        assertEquals(AdbToolboxTheme.Typography.mono, field(component, "adbPathField").font)
        assertEquals(AdbToolboxTheme.Typography.mono, field(component, "scrcpyPathField").font)
        assertEquals(AdbToolboxTheme.Typography.mono, field(component, "captureDirectoryField").font)
        assertEquals(AdbToolboxTheme.Typography.mono, field(component, "logcatBufferSizeKbField").font)
        configurable.disposeUIResources()
    }

    fun `test create component presents every persisted task 038 setting`() {
        val backend = FakeSettingsEditorBackend(
            persisted = SettingsState(
                adbPathOverride = "/tools/adb",
                scrcpyPathOverride = "/tools/scrcpy",
                captureDirectory = "/captures",
                logcatBufferSizeKb = 8192,
            ),
        )
        val configurable = AdbToolboxSettingsConfigurable(backend)

        val component = configurable.createComponent()

        assertEquals("/tools/adb", field(component, "adbPathField").text)
        assertEquals("/tools/scrcpy", field(component, "scrcpyPathField").text)
        assertEquals("/captures", field(component, "captureDirectoryField").text)
        assertEquals("8192", field(component, "logcatBufferSizeKbField").text)
        assertEquals("Device Cockpit", configurable.displayName)
        configurable.disposeUIResources()
    }

    fun `test custom TalkBack commands are shown, mark the form modified and are applied`() {
        val backend = FakeSettingsEditorBackend(persisted = SettingsState(talkBackOnCommand = "settings put secure a 1"))
        val configurable = AdbToolboxSettingsConfigurable(backend)
        val component = configurable.createComponent()
        val onField = field(component, "talkBackOnCommandField")
        val offField = field(component, "talkBackOffCommandField")
        assertEquals("settings put secure a 1", onField.text)
        assertEquals("", offField.text)

        offField.text = "settings put secure a 0"
        assertTrue(configurable.isModified)
        configurable.apply()

        assertEquals("settings put secure a 1", backend.candidates.last().talkBackOnCommand)
        assertEquals("settings put secure a 0", backend.candidates.last().talkBackOffCommand)
        configurable.disposeUIResources()
    }

    fun `test reset restores persisted values and clears modified state`() {
        val backend = FakeSettingsEditorBackend(persisted = SettingsState(logcatBufferSizeKb = 4096))
        val configurable = AdbToolboxSettingsConfigurable(backend)
        val component = configurable.createComponent()
        val bufferField = field(component, "logcatBufferSizeKbField")
        bufferField.text = "2048"
        assertTrue(configurable.isModified)

        configurable.reset()

        assertEquals("4096", bufferField.text)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun `test modified checks compare locally without reloading settings on the EDT`() {
        val backend = FakeSettingsEditorBackend()
        val configurable = AdbToolboxSettingsConfigurable(backend)
        val component = configurable.createComponent()
        assertEquals(1, backend.readCount)

        field(component, "adbPathField").text = "/edited/adb"
        assertTrue(configurable.isModified)
        assertTrue(configurable.isModified)

        assertEquals(1, backend.readCount)
        configurable.disposeUIResources()
    }

    fun `test apply persists normalized valid values and clears modified state`() {
        val backend = FakeSettingsEditorBackend { candidate ->
            SettingsApplyResult.Applied(
                candidate.copy(
                    adbPathOverride = null,
                    scrcpyPathOverride = null,
                    captureDirectory = null,
                    talkBackOnCommand = null,
                    talkBackOffCommand = null,
                ),
            )
        }
        val configurable = AdbToolboxSettingsConfigurable(backend)
        val component = configurable.createComponent()
        field(component, "adbPathField").text = "   "
        field(component, "scrcpyPathField").text = ""
        field(component, "captureDirectoryField").text = "  "
        field(component, "logcatBufferSizeKbField").text = "2048"

        configurable.apply()

        assertEquals(SettingsState(logcatBufferSizeKb = 2048), backend.persisted)
        assertEquals(1, backend.candidates.size)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    fun `test apply rejects malformed or out of range buffer without persisting`() {
        val original = SettingsState(logcatBufferSizeKb = 4096)
        val backend = FakeSettingsEditorBackend(persisted = original)
        val configurable = AdbToolboxSettingsConfigurable(backend)
        val component = configurable.createComponent()

        field(component, "logcatBufferSizeKbField").text = "not-a-number"
        assertThrows(ConfigurationException::class.java) { configurable.apply() }
        assertTrue(backend.candidates.isEmpty())

        backend.result = SettingsApplyResult.Invalid(setOf(SettingsFieldError.LogcatBufferSizeOutOfRange))
        field(component, "logcatBufferSizeKbField").text = "63"
        assertThrows(ConfigurationException::class.java) { configurable.apply() }

        assertEquals(original, backend.persisted)
        assertEquals(1, backend.candidates.size)
        configurable.disposeUIResources()
    }

    fun `test apply reports invalid tool paths instead of silently saving them`() {
        val backend = FakeSettingsEditorBackend(
            result = SettingsApplyResult.Invalid(setOf(SettingsFieldError.AdbPathNotExecutable)),
        )
        val configurable = AdbToolboxSettingsConfigurable(backend)
        val component = configurable.createComponent()
        field(component, "adbPathField").text = "/definitely/missing/adb"

        val error = expectConfigurationException { configurable.apply() }

        assertTrue(error.localizedMessage.orEmpty().contains("ADB executable"))
        assertEquals(SettingsState.DEFAULT, backend.persisted)
        assertEquals(1, backend.candidates.size)
        configurable.disposeUIResources()
    }

    fun `test dispose releases the form and recreates it from persisted state`() {
        val backend = FakeSettingsEditorBackend()
        val configurable = AdbToolboxSettingsConfigurable(backend)
        val first = configurable.createComponent()
        field(first, "logcatBufferSizeKbField").text = "2048"

        configurable.disposeUIResources()
        assertEquals(1, backend.disposeCount)
        val second = configurable.createComponent()

        assertNotSame(first, second)
        assertEquals(SettingsState.DEFAULT_LOGCAT_BUFFER_SIZE_KB.toString(), field(second, "logcatBufferSizeKbField").text)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }

    private fun field(root: Component, name: String): JTextField =
        findNamed(root, name) as? JTextField ?: error("Missing text field '$name'")

    private fun findNamed(component: Component, name: String): Component? {
        if (component.name == name) return component
        if (component is Container) {
            component.components.forEach { child ->
                findNamed(child, name)?.let { return it }
            }
        }
        return null
    }

    private fun expectConfigurationException(block: () -> Unit): ConfigurationException = try {
        block()
        error("Expected ConfigurationException")
    } catch (error: ConfigurationException) {
        error
    }

    private class FakeSettingsEditorBackend(
        var persisted: SettingsState = SettingsState.DEFAULT,
        var result: SettingsApplyResult? = null,
        private val resultFactory: ((SettingsState) -> SettingsApplyResult)? = null,
    ) : SettingsEditorBackend {
        val candidates = mutableListOf<SettingsState>()
        var disposeCount = 0
        var readCount = 0

        override fun read(): SettingsState {
            readCount++
            return persisted
        }

        override fun apply(candidate: SettingsState): SettingsApplyResult {
            candidates += candidate
            val applyResult = result ?: resultFactory?.invoke(candidate) ?: SettingsApplyResult.Applied(candidate)
            if (applyResult is SettingsApplyResult.Applied) persisted = applyResult.state
            return applyResult
        }

        override fun dispose() {
            disposeCount++
        }
    }
}
