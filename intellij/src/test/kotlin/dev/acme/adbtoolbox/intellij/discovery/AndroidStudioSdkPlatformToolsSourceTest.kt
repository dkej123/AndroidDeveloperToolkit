package dev.acme.adbtoolbox.intellij.discovery

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class AndroidStudioSdkPlatformToolsSourceTest : BasePlatformTestCase() {

    fun `test uses the platform-tools directory of the adb selected by Android Studio`() {
        assertEquals(
            "/opt/android-sdk/platform-tools",
            platformToolsDirectory(File("/opt/android-sdk/platform-tools/adb")),
        )
    }

    fun `test reports no directory when Android Studio has no adb`() {
        assertNull(platformToolsDirectory(null))
    }
}
