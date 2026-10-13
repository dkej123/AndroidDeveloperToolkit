package io.github.dkej123.devicecockpit.intellij.apps.details

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalysis
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkCatalog
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkTarget
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkTargetKind
import io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState
import io.github.dkej123.devicecockpit.domain.deeplinks.UriMatcher
import io.github.dkej123.devicecockpit.domain.deeplinks.UriMatcherKind
import io.github.dkej123.devicecockpit.domain.deeplinks.UriPattern

class DeepLinksListTest : BasePlatformTestCase() {
    private val opened = mutableListOf<String>()

    private val analysis = DeepLinkAnalysis(
        DeepLinkCatalog(
            "com.acme",
            listOf(
                DeepLinkTarget(
                    "com.acme.DeepLinkActivity",
                    DeepLinkTargetKind.ACTIVITY,
                    patterns = listOf(
                        UriPattern(schemes = setOf("acme"), hosts = setOf("map", "sessions")),
                        UriPattern(
                            schemes = setOf("https"),
                            hosts = setOf("acme.com"),
                            paths = listOf(UriMatcher(UriMatcherKind.PATH_PATTERN, "/station/.*")),
                            autoVerify = true,
                        ),
                    ),
                    hasDefaultCategory = true,
                ),
            ),
        ),
        verifications = listOf(AppLinkVerification("acme.com", DeviceLinkState.VERIFIED)),
    )

    private fun list() = DeepLinksList(onAnalyze = {}, onOpen = { opened += it }, onCopy = {}).apply {
        render(analysis, "com.acme", loading = false, error = null)
    }

    fun `test every link is a row with its parameters and app-link state`() {
        val list = list()

        assertEquals(listOf("acme://map", "acme://sessions", "https://acme.com/station/{station}"), list.templatesForTest)
        assertEquals(listOf("Verified"), list.chipsForTest("https://acme.com/station/{station}"))
        assertEquals("station*", list.parametersForTest("https://acme.com/station/{station}"))
        assertEquals("No parameters declared — add your own", list.parametersForTest("acme://map"))
    }

    fun `test opening a row unfolds the launcher under it, opening it again folds it`() {
        val list = list()

        list.openForTest("acme://map")
        assertTrue(list.launcherShownForTest)
        list.launcherForTest.openForTest.doClick()
        assertEquals(listOf("acme://map"), opened)

        list.openForTest("acme://map")
        assertFalse(list.launcherShownForTest)
    }

    fun `test the filter keeps matching links only`() {
        val list = list()

        list.filterForTest = "station"

        assertEquals(listOf("https://acme.com/station/{station}"), list.templatesForTest)
    }
}
