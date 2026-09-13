package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.settings.SettingsState

/**
 * Headless platform tests for task 038's persistence adapter — same `:intellij:test` sandbox as
 * [DeviceSelectionPersistenceAdapterTest] (see its class doc for why `...Now` functions are
 * exercised directly instead of the port's `suspend` methods, and why this stays a
 * [BasePlatformTestCase]).
 */
class SettingsPersistenceAdapterTest : BasePlatformTestCase() {

    fun `test a fresh project has default settings`() {
        val adapter = SettingsPersistenceAdapter(AdbToolboxProjectState())

        assertEquals(SettingsState.DEFAULT, adapter.readSettingsNow())
    }

    fun `test a written settings state round-trips back out`() {
        val adapter = SettingsPersistenceAdapter(AdbToolboxProjectState())
        val settings = SettingsState(
            adbPathOverride = "/opt/tools/adb",
            scrcpyPathOverride = "/opt/tools/scrcpy",
            captureDirectory = "/home/user/captures",
            logcatBufferSizeKb = 32768,
        )

        adapter.writeSettingsNow(settings)

        assertEquals(settings, adapter.readSettingsNow())
    }

    fun `test writing a fresh SettingsState clears previously written overrides`() {
        val adapter = SettingsPersistenceAdapter(AdbToolboxProjectState())
        adapter.writeSettingsNow(SettingsState(adbPathOverride = "/opt/tools/adb"))

        adapter.writeSettingsNow(SettingsState.DEFAULT)

        assertEquals(SettingsState.DEFAULT, adapter.readSettingsNow())
    }

    fun `test loadState composes via XmlSerializerUtil so a re-loaded settings state survives`() {
        val projectState = AdbToolboxProjectState()
        val adapter = SettingsPersistenceAdapter(projectState)
        val settings = SettingsState(adbPathOverride = "/opt/tools/adb", logcatBufferSizeKb = 2048)
        adapter.writeSettingsNow(settings)

        val reloaded = AdbToolboxProjectState()
        reloaded.loadState(projectState.state)

        assertEquals(settings, SettingsPersistenceAdapter(reloaded).readSettingsNow())
    }

    fun `test AdbToolboxProjectState carrying the settings slice is registered at project scope, not application scope`() {
        val serviceAnnotation = AdbToolboxProjectState::class.java.getAnnotation(Service::class.java)

        assertNotNull(serviceAnnotation)
        assertEquals(listOf(Service.Level.PROJECT), serviceAnnotation.value.toList())
    }

    fun `test settings persist against the real project-scoped service instance`() {
        val projectState = project.service<AdbToolboxProjectState>()
        val adapter = SettingsPersistenceAdapter(projectState)

        adapter.writeSettingsNow(SettingsState(captureDirectory = "/home/user/captures"))

        assertEquals(
            "/home/user/captures",
            project.service<AdbToolboxProjectState>().state.settings.captureDirectory,
        )
    }
}
