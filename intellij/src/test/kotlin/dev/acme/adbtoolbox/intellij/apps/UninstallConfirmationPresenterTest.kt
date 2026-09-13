package dev.acme.adbtoolbox.intellij.apps

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.domain.apps.UninstallConfirmation

class UninstallConfirmationPresenterTest : BasePlatformTestCase() {
    fun `test dialog keeps Cancel first and default while Uninstall is never default`() {
        val spec = uninstallDialogSpec("com.acme.shop", "Pixel 8 Pro")

        assertEquals(listOf("Cancel", "Uninstall"), spec.options)
        assertEquals(spec.cancelOptionIndex, spec.defaultOptionIndex)
        assertEquals(spec.cancelOptionIndex, spec.focusedOptionIndex)
        assertTrue(spec.destructiveOptionIndex != spec.defaultOptionIndex)
    }

    fun `test dialog uses the supplied package and device label in the approved copy`() {
        val spec = uninstallDialogSpec("com.acme.shop", "Pixel 8 Pro")

        assertEquals("Uninstall com.acme.shop?", spec.title)
        assertEquals(
            "Removes the app and all of its data from Pixel 8 Pro. This cannot be undone.",
            spec.message,
        )
    }

    fun `test only Uninstall confirms while Cancel Escape or close cancel`() {
        val spec = uninstallDialogSpec("com.acme.shop", "Pixel 8 Pro")

        assertEquals(UninstallConfirmation.Confirmed, uninstallConfirmationForDialogResult(spec.destructiveOptionIndex, spec))
        assertEquals(UninstallConfirmation.Cancelled, uninstallConfirmationForDialogResult(spec.cancelOptionIndex, spec))
        assertEquals(UninstallConfirmation.Cancelled, uninstallConfirmationForDialogResult(-1, spec))
    }
}
