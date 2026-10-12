package io.github.dkej123.devicecockpit.intellij.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalysisResult
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalyzer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LazyDeepLinkAnalyzerTest {
    private val serial = DeviceSerial.of("emulator-5554")
    private fun LazyDeepLinkAnalyzer.run() = runBlocking { analyze(serial, 0, "com.acme.shop", null, null, null, refresh = true) }

    @Test
    fun `a missing apkanalyzer says what to install instead of failing silently`() {
        val result = LazyDeepLinkAnalyzer { null }.run()

        assertEquals(DeepLinkAnalysisResult.Failure(APKANALYZER_MISSING), result)
    }

    @Test
    fun `an apkanalyzer installed after the first try is picked up without restarting the IDE`() {
        var installed: DeepLinkAnalyzer? = null
        var resolutions = 0
        val lazy = LazyDeepLinkAnalyzer { resolutions++; installed }
        lazy.run()
        installed = DeepLinkAnalyzer { _, _, _, _, _, _, _ -> DeepLinkAnalysisResult.NoCache }

        assertEquals(DeepLinkAnalysisResult.NoCache, lazy.run())
        lazy.run()
        assertEquals("resolved once found, then reused", 2, resolutions)
    }

    @Test
    fun `the sdk is found in the per-OS default install location`() {
        val home = Files.createTempDirectory("home")
        val mac = Files.createDirectories(home.resolve("Library/Android/sdk"))

        assertEquals(mac, androidSdkDirectory(candidates = defaultSdkLocations("Mac OS X", home.toString(), null)))
        assertNull(androidSdkDirectory(candidates = defaultSdkLocations("Linux", home.toString(), null)))
    }

    @Test
    fun `windows looks under LOCALAPPDATA`() {
        assertEquals(listOf(Path.of("C:/Users/dev/AppData/Local", "Android", "Sdk")), defaultSdkLocations("Windows 11", "C:/Users/dev", "C:/Users/dev/AppData/Local"))
    }
}
