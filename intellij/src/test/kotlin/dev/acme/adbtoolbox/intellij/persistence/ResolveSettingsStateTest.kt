package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.settings.SettingsState

/**
 * [resolveSettingsState] needs no IntelliJ Platform API — it is a pure function over a plain bean —
 * but is kept as a [BasePlatformTestCase] like every other test in this module (see
 * [ResolvePersistedSerialTest]'s class doc for why). Covers task 038's default/corrupt/migration
 * graceful-degradation requirement: missing, corrupt, older-but-recognized-schema, and
 * newer-unrecognized-schema persisted data must all resolve sensibly, never throw.
 */
class ResolveSettingsStateTest : BasePlatformTestCase() {

    fun `test missing (default, never-persisted) state resolves to all defaults`() {
        assertEquals(SettingsState.DEFAULT, resolveSettingsState(SettingsPersistenceState()))
    }

    fun `test a fully populated current-schema state resolves with every field`() {
        val state = SettingsPersistenceState().apply {
            adbPathOverride = "/opt/tools/adb"
            scrcpyPathOverride = "/opt/tools/scrcpy"
            captureDirectory = "/home/user/captures"
            logcatBufferSizeKb = 8192
        }

        assertEquals(
            SettingsState(
                adbPathOverride = "/opt/tools/adb",
                scrcpyPathOverride = "/opt/tools/scrcpy",
                captureDirectory = "/home/user/captures",
                logcatBufferSizeKb = 8192,
            ),
            resolveSettingsState(state),
        )
    }

    fun `test a blank path override is treated as corrupt and resolves to no override`() {
        val state = SettingsPersistenceState().apply {
            adbPathOverride = "   "
            captureDirectory = ""
        }

        val resolved = resolveSettingsState(state)

        assertNull(resolved.adbPathOverride)
        assertNull(resolved.captureDirectory)
    }

    fun `test an out-of-range buffer size resolves to the default buffer size`() {
        val tooLow = SettingsPersistenceState().apply { logcatBufferSizeKb = -5 }
        val tooHigh = SettingsPersistenceState().apply {
            logcatBufferSizeKb = SettingsState.MAX_LOGCAT_BUFFER_SIZE_KB + 1
        }

        assertEquals(SettingsState.DEFAULT_LOGCAT_BUFFER_SIZE_KB, resolveSettingsState(tooLow).logcatBufferSizeKb)
        assertEquals(SettingsState.DEFAULT_LOGCAT_BUFFER_SIZE_KB, resolveSettingsState(tooHigh).logcatBufferSizeKb)
    }

    fun `test a recognized older schema version migrates and preserves its data rather than discarding it`() {
        val legacy = SettingsPersistenceState().apply {
            schemaVersion = LEGACY_SETTINGS_SCHEMA_VERSION_V0
            adbPathOverride = "/legacy/adb"
            scrcpyPathOverride = "/legacy/scrcpy"
            captureDirectory = "/legacy/captures"
            logcatBufferSizeKb = 4096
        }

        assertEquals(
            SettingsState(
                adbPathOverride = "/legacy/adb",
                scrcpyPathOverride = "/legacy/scrcpy",
                captureDirectory = "/legacy/captures",
                logcatBufferSizeKb = 4096,
            ),
            resolveSettingsState(legacy),
        )
    }

    fun `test migrateSettingsState bumps a recognized older version to the current schema version`() {
        val legacy = SettingsPersistenceState().apply { schemaVersion = LEGACY_SETTINGS_SCHEMA_VERSION_V0 }

        val migrated = migrateSettingsState(legacy)

        assertEquals(SettingsPersistenceState.CURRENT_SCHEMA_VERSION, migrated?.schemaVersion)
    }

    fun `test a newer, not-yet-understood schema version resolves to all defaults`() {
        val state = SettingsPersistenceState().apply {
            schemaVersion = SettingsPersistenceState.CURRENT_SCHEMA_VERSION + 1
            adbPathOverride = "/should/not/survive"
        }

        assertEquals(SettingsState.DEFAULT, resolveSettingsState(state))
    }
}
