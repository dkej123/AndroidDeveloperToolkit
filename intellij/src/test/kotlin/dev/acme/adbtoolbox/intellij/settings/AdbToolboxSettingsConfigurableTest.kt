package dev.acme.adbtoolbox.intellij.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.settings.SettingsApplyResult
import dev.acme.adbtoolbox.domain.settings.SettingsState
import dev.acme.adbtoolbox.domain.settings.SettingsFieldError
import java.awt.Component
import java.awt.Container
import javax.swing.JTextField

class AdbToolboxSettingsConfigurableTest : BasePlatformTestCase() {

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
        assertEquals("ADB Toolbox", configurable.displayName)
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
