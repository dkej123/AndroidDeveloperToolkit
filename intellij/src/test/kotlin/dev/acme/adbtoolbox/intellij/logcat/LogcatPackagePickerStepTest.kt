package dev.acme.adbtoolbox.intellij.logcat

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.logcat.LogcatPackageChoice

class LogcatPackagePickerStepTest : BasePlatformTestCase() {

    private val choices = listOf(
        LogcatPackageChoice("com.acme.zebra", "Zebra", pinned = true),
        LogcatPackageChoice("com.acme.alpha", "Alpha", pinned = false),
        LogcatPackageChoice("com.acme.raw", "com.acme.raw", pinned = false),
    )

    fun `test all packages comes first, pinned apps get their own section`() {
        val step = LogcatPackagePickerStep(choices) {}

        assertEquals(
            listOf("All packages", "Zebra  ·  com.acme.zebra", "Alpha  ·  com.acme.alpha", "com.acme.raw"),
            step.values.map(step::getTextFor),
        )
        assertEquals(listOf(null, "Pinned", "Apps", null), step.values.map { step.getSeparatorAbove(it)?.text })
        assertTrue(step.isSpeedSearchEnabled)
    }

    fun `test choosing an app or all packages reports the package name or null`() {
        val picked = mutableListOf<String?>()
        val step = LogcatPackagePickerStep(choices) { picked += it }

        step.onChosen(step.values[2], true)
        step.onChosen(step.values[0], true)

        assertEquals(listOf("com.acme.alpha", null), picked)
    }
}
