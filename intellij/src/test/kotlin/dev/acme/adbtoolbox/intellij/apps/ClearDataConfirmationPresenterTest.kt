package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.apps.ClearDataConfirmation

class ClearDataConfirmationPresenterTest : BasePlatformTestCase() {
    fun `test dialog keeps Cancel first and default while the destructive action is never default`() {
        val spec = clearDataDialogSpec("com.acme.shop", "Pixel 8 Pro")
        assertEquals(listOf("Cancel", "Clear data"), spec.options)
        assertEquals(spec.cancelOptionIndex, spec.defaultOptionIndex)
        assertEquals(spec.cancelOptionIndex, spec.focusedOptionIndex)
        assertTrue(spec.destructiveOptionIndex != spec.defaultOptionIndex)
    }

    fun `test dialog uses the supplied package and device label in the approved copy`() {
        val spec = clearDataDialogSpec("com.acme.shop", "Pixel 8 Pro")
        assertEquals("Clear data for com.acme.shop?", spec.title)
        assertEquals(
            "Deletes databases, preferences and caches on Pixel 8 Pro, and signs the user out. This cannot be undone.",
            spec.message,
        )
    }

    fun `test only the destructive option confirms while Escape or close cancels`() {
        val spec = clearDataDialogSpec("com.acme.shop", "Pixel 8 Pro")

        assertEquals(ClearDataConfirmation.Confirmed, confirmationForDialogResult(spec.destructiveOptionIndex, spec))
        assertEquals(ClearDataConfirmation.Cancelled, confirmationForDialogResult(spec.cancelOptionIndex, spec))
        assertEquals(ClearDataConfirmation.Cancelled, confirmationForDialogResult(-1, spec))
    }
}
