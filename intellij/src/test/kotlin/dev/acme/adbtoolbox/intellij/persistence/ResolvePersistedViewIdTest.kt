package dev.acme.adbtoolbox.intellij.persistence

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.nav.ViewId

/**
 * [resolvePersistedViewId] needs no IntelliJ Platform API — it is a pure function over a plain
 * bean — but is kept as a [BasePlatformTestCase] like every other test in this module:
 * [dev.acme.adbtoolbox.intellij.composition.AdbTransportSelectionTest]'s class doc documents that a
 * plain JUnit 5 `@Test` class here is not discovered by `:intellij:test`'s IntelliJ-Platform-aware
 * runner. Covers task 012's graceful-degradation requirement: missing, corrupt, unrecognized, and
 * older/newer-schema persisted data must all resolve to `null` ("no persisted view — use the
 * default") rather than throw.
 */
class ResolvePersistedViewIdTest : BasePlatformTestCase() {

    fun `test a valid route key at the current schema version resolves`() {
        val state = NavigationPersistenceState().apply { lastView = "logcat" }

        assertEquals(ViewId.Logcat, resolvePersistedViewId(state))
    }

    fun `test every destination's own route key resolves back to itself`() {
        ViewId.entries.forEach { viewId ->
            val state = NavigationPersistenceState().apply { lastView = viewId.routeKey }

            assertEquals(viewId, resolvePersistedViewId(state))
        }
    }

    fun `test missing (default, never-persisted) state resolves to null`() {
        assertNull(resolvePersistedViewId(NavigationPersistenceState()))
    }

    fun `test a blank route key is treated as corrupt and resolves to null`() {
        val state = NavigationPersistenceState().apply { lastView = "" }

        assertNull(resolvePersistedViewId(state))
    }

    fun `test a route key no ViewId owns is treated as corrupt and resolves to null`() {
        val state = NavigationPersistenceState().apply { lastView = "not-a-real-view" }

        assertNull(resolvePersistedViewId(state))
    }

    fun `test an older schema version resolves to null rather than trusting a shape this class may not match`() {
        val state = NavigationPersistenceState().apply {
            schemaVersion = 0
            lastView = "device"
        }

        assertNull(resolvePersistedViewId(state))
    }

    fun `test a newer, not-yet-understood schema version resolves to null`() {
        val state = NavigationPersistenceState().apply {
            schemaVersion = NavigationPersistenceState.CURRENT_SCHEMA_VERSION + 1
            lastView = "device"
        }

        assertNull(resolvePersistedViewId(state))
    }
}
