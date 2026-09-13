package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptions

/**
 * [resolveMirroringOptionsState] needs no IntelliJ Platform API — it is a pure function over a plain
 * bean — but is kept as a [BasePlatformTestCase] like every other test in this module (see
 * [ResolveSettingsStateTest]'s class doc for why). Covers task 040's default/corrupt/migration
 * graceful-degradation requirement: missing, corrupt (out-of-range), older-but-recognized-schema,
 * and newer-unrecognized-schema persisted data must all resolve sensibly, never throw.
 */
class ResolveMirroringOptionsStateTest : BasePlatformTestCase() {

    fun `test missing (default, never-persisted) state resolves to all defaults`() {
        assertEquals(MirroringOptions.DEFAULT, resolveMirroringOptionsState(MirroringOptionsPersistenceState()))
    }

    fun `test a fully populated current-schema state resolves with every field`() {
        val state = MirroringOptionsPersistenceState().apply {
            stayAwake = true
            showTouches = true
            maxSize = 1920
            videoBitRateMbps = 8
        }

        assertEquals(
            MirroringOptions(stayAwake = true, showTouches = true, maxSize = 1920, videoBitRateMbps = 8),
            resolveMirroringOptionsState(state),
        )
    }

    fun `test the NO_LIMIT sentinel resolves to a null maxSize and bit rate, not zero`() {
        val state = MirroringOptionsPersistenceState().apply {
            maxSize = MirroringOptionsPersistenceState.NO_LIMIT
            videoBitRateMbps = MirroringOptionsPersistenceState.NO_LIMIT
        }

        val resolved = resolveMirroringOptionsState(state)

        assertNull(resolved.maxSize)
        assertNull(resolved.videoBitRateMbps)
    }

    fun `test an out-of-range max size or bit rate resolves to no override rather than throwing`() {
        val tooHighMaxSize = MirroringOptionsPersistenceState().apply {
            maxSize = MirroringOptions.MAX_MAX_SIZE_PX + 1
        }
        val negativeBitRate = MirroringOptionsPersistenceState().apply { videoBitRateMbps = -5 }

        assertNull(resolveMirroringOptionsState(tooHighMaxSize).maxSize)
        assertNull(resolveMirroringOptionsState(negativeBitRate).videoBitRateMbps)
    }

    fun `test a recognized older schema version migrates and preserves its data rather than discarding it`() {
        val legacy = MirroringOptionsPersistenceState().apply {
            schemaVersion = LEGACY_MIRRORING_OPTIONS_SCHEMA_VERSION_V0
            stayAwake = true
            maxSize = 1280
        }

        assertEquals(
            MirroringOptions(stayAwake = true, maxSize = 1280),
            resolveMirroringOptionsState(legacy),
        )
    }

    fun `test migrateMirroringOptionsState bumps a recognized older version to the current schema version`() {
        val legacy = MirroringOptionsPersistenceState().apply { schemaVersion = LEGACY_MIRRORING_OPTIONS_SCHEMA_VERSION_V0 }

        val migrated = migrateMirroringOptionsState(legacy)

        assertEquals(MirroringOptionsPersistenceState.CURRENT_SCHEMA_VERSION, migrated?.schemaVersion)
    }

    fun `test a newer, not-yet-understood schema version resolves to all defaults`() {
        val state = MirroringOptionsPersistenceState().apply {
            schemaVersion = MirroringOptionsPersistenceState.CURRENT_SCHEMA_VERSION + 1
            stayAwake = true
            maxSize = 1920
        }

        assertEquals(MirroringOptions.DEFAULT, resolveMirroringOptionsState(state))
    }
}
