package dev.acme.adbtoolbox.intellij.composition

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.adapters.adb.selector.FallbackAdbTransport
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport

/**
 * [selectAdbTransport] needs no IntelliJ Platform API (it is pure, over two already-constructed
 * [dev.acme.adbtoolbox.domain.adb.AdbTransport]s) but is kept as a [BasePlatformTestCase] like
 * [AdbToolboxProjectServiceTest]: a plain JUnit 5 `@Test` class in this module is not discovered by
 * `:intellij:test`'s IntelliJ-Platform-aware test runner (`testFramework(TestFrameworkType.Platform)`,
 * task 007's ADR-0005-required Android-plugin compile dependency) — verified directly, not assumed.
 *
 * These tests read [FallbackAdbTransport]'s private `primary`/`fallback` fields via reflection
 * instead of calling its (`suspend`) `executeText` — this sandbox's bundled Kotlin stdlib is older
 * than what this project's pinned Kotlin compiler (2.2.20) emits `suspend` calls against (missing
 * `kotlin.coroutines.jvm.internal.SpillingKt`, verified directly by hitting the failure), so
 * `:intellij`'s platform tests avoid invoking `suspend` functions belonging to other modules'
 * compiled output. [FallbackAdbTransport]'s own fallback *behavior* (not just wiring) is already
 * covered by `FallbackAdbTransportTest` in `:adapters-adb` (task 006), which is free of this
 * constraint — it is a plain JUnit 5 module with no IntelliJ Platform test sandbox.
 */
class AdbTransportSelectionTest : BasePlatformTestCase() {

    fun `test when the Android plugin is present ddmlib is wired as primary with binary as fallback`() {
        val ddmlib = FakeAdbTransport()
        val binary = FakeAdbTransport()

        val selected = selectAdbTransport(androidPluginPresent = true, ddmlibTransport = ddmlib, binaryTransport = binary)

        assertTrue(selected is FallbackAdbTransport)
        val fallback = selected as FallbackAdbTransport
        assertSame(ddmlib, primaryOf(fallback))
        assertSame(binary, fallbackOf(fallback))
    }

    fun `test when the Android plugin is absent the binary transport is used directly, never wrapped`() {
        val ddmlib = FakeAdbTransport()
        val binary = FakeAdbTransport()

        val selected = selectAdbTransport(androidPluginPresent = false, ddmlibTransport = ddmlib, binaryTransport = binary)

        assertSame(binary, selected)
        assertFalse(selected is FallbackAdbTransport)
    }

    private fun primaryOf(transport: FallbackAdbTransport) = privateField(transport, "primary")
    private fun fallbackOf(transport: FallbackAdbTransport) = privateField(transport, "fallback")

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
