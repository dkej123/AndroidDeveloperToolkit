package io.github.dkej123.devicecockpit.intellij.persistence

import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkLaunchValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeepLinkLaunchPersistenceAdapterTest {
    private val projectState = DeviceCockpitProjectState()
    private val adapter = DeepLinkLaunchPersistenceAdapter(projectState)

    @Test
    fun `values are remembered per app and template, survive a state reload and can be cleared`() {
        val values = DeepLinkLaunchValues(mapOf("userId" to "42"), listOf("utm_source" to "cockpit"))
        adapter.save("com.acme", "app://acme/user/{userId}", values)

        val reloaded = DeviceCockpitProjectState().apply { loadState(projectState.state) }
        assertEquals(values, DeepLinkLaunchPersistenceAdapter(reloaded).load("com.acme", "app://acme/user/{userId}"))
        assertNull(adapter.load("com.other", "app://acme/user/{userId}"))

        adapter.save("com.acme", "app://acme/user/{userId}", DeepLinkLaunchValues(mapOf("userId" to "")))
        assertNull(adapter.load("com.acme", "app://acme/user/{userId}"))
    }

    @Test
    fun `only the most recent links are kept`() {
        repeat(205) { adapter.save("com.acme", "app://acme/$it", DeepLinkLaunchValues(extras = listOf("k" to "$it"))) }

        assertNull(adapter.load("com.acme", "app://acme/0"))
        assertEquals(listOf("k" to "204"), adapter.load("com.acme", "app://acme/204")?.extras)
        assertEquals(200, projectState.state.deepLinkLaunches.entries.size)
    }
}
