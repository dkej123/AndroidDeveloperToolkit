package dev.acme.adbtoolbox.intellij.apps.details

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.acme.adbtoolbox.application.appdetails.AppDetailsIntent
import dev.acme.adbtoolbox.application.appdetails.AppDetailsState
import dev.acme.adbtoolbox.application.appdetails.DatabaseEditorState
import dev.acme.adbtoolbox.application.appdetails.FileAccessState
import dev.acme.adbtoolbox.application.appdetails.PrefsEditorState
import dev.acme.adbtoolbox.domain.appdata.AppDataAccess
import dev.acme.adbtoolbox.domain.appdata.AppDetailsParser
import dev.acme.adbtoolbox.domain.appdata.PrefEntry
import dev.acme.adbtoolbox.domain.appdata.PrefType
import dev.acme.adbtoolbox.domain.appdata.PrefValue
import dev.acme.adbtoolbox.domain.appdata.SqlRows
import dev.acme.adbtoolbox.domain.appdata.SqlValue
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkAnalysis
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkCatalog
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkTarget
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkTargetKind
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkSource
import dev.acme.adbtoolbox.domain.deeplinks.AppLinkVerification
import dev.acme.adbtoolbox.domain.deeplinks.DeviceLinkState
import dev.acme.adbtoolbox.domain.deeplinks.UriPattern

class AppDetailsPanelTest : BasePlatformTestCase() {

    private val details = AppDetailsParser.parse(
        "com.acme.shop",
        "Packages:\n  Package [com.acme.shop] (1):\n    userId=10081\n    versionCode=47 minSdk=23 targetSdk=34\n    versionName=2.27\n",
    )

    private fun state() = AppDetailsState(
        packageName = "com.acme.shop",
        label = "Acme Shop",
        details = details,
        runningPid = "4242",
        access = FileAccessState.Available(AppDataAccess.RunAs),
        sharedPrefsFiles = listOf("prefs.xml"),
        databaseFiles = listOf("app.db"),
        prefs = PrefsEditorState("prefs.xml", listOf(PrefEntry("launches", PrefValue.IntValue(3))), loading = false),
        database = DatabaseEditorState(
            "app.db",
            tables = listOf("users"),
            table = "users",
            page = SqlRows(listOf("name"), listOf(listOf(SqlValue.Text("Ann"))), rowIds = listOf(1), totalRows = 1),
            loading = false,
        ),
    )

    fun `test the info tab shows the parsed details and the running process`() {
        val panel = AppDetailsPanel {}

        panel.update(state())

        assertEquals("Acme Shop", panel.titleForTest)
        assertTrue(panel.infoTextForTest.contains("2.27"))
        assertTrue(panel.infoTextForTest.contains("23 / 34"))
        assertTrue(panel.infoTextForTest.contains("running · pid 4242"))
        assertTrue(panel.infoTextForTest.contains("run-as"))
    }

    fun `test editing a pref value forwards a typed PutPref, never changing the table itself`() {
        val intents = mutableListOf<AppDetailsIntent>()
        val panel = AppDetailsPanel { intents += it }
        panel.update(state())

        panel.prefsModelForTest.setValueAt("9", 0, 2)

        assertEquals(listOf(AppDetailsIntent.PutPref("launches", "launches", PrefType.Int, "9")), intents)
        assertEquals("3", panel.prefsModelForTest.getValueAt(0, 2))
    }

    fun `test save is only enabled with unsaved changes`() {
        val panel = AppDetailsPanel {}
        panel.update(state())
        assertFalse(panel.prefsSaveForTest.isEnabled)

        panel.update(state().let { it.copy(prefs = it.prefs!!.copy(dirty = true)) })

        assertTrue(panel.prefsSaveForTest.isEnabled)
    }

    fun `test a database cell edit forwards EditCell and NULL means SQL NULL`() {
        val intents = mutableListOf<AppDetailsIntent>()
        val panel = AppDetailsPanel { intents += it }
        panel.update(state())

        assertTrue(panel.rowsModelForTest.isCellEditable(0, 0))
        panel.rowsModelForTest.setValueAt("Bea", 0, 0)
        panel.rowsModelForTest.setValueAt("NULL", 0, 0)

        assertEquals(listOf(AppDetailsIntent.EditCell(0, "name", "Bea"), AppDetailsIntent.EditCell(0, "name", null)), intents)
    }

    fun `test without file access the editors explain why`() {
        val panel = AppDetailsPanel {}

        panel.update(state().copy(access = FileAccessState.Unavailable("needs a debuggable build")))

        assertEquals("needs a debuggable build", panel.accessMessageForTest())
    }

    fun `test the first prefs file and database open automatically once per app`() {
        val intents = mutableListOf<AppDetailsIntent>()
        val panel = AppDetailsPanel { intents += it }
        val fresh = state().copy(prefs = null, database = null)

        panel.update(fresh)
        panel.update(fresh)

        assertEquals(listOf(AppDetailsIntent.OpenPrefs("prefs.xml"), AppDetailsIntent.OpenDatabase("app.db")), intents)
    }

    fun `test details expose deep links and permissions as dedicated tabs`() {
        val panel = AppDetailsPanel {}
        panel.update(state().copy(deepLinks = DeepLinkAnalysis(DeepLinkCatalog("com.acme.shop", listOf(
            DeepLinkTarget("com.acme.shop.Main", DeepLinkTargetKind.ACTIVITY, patterns = listOf(UriPattern(schemes = setOf("https"), hosts = setOf("example.com"))), hasDefaultCategory = true),
        )))))

        assertEquals(listOf("Info", "Deep Links", "Permissions", "Shared prefs", "Databases"),
            (0 until panel.tabsForTest.tabCount).map(panel.tabsForTest::getTitleAt))
        assertEquals(1, panel.deepLinksRowCountForTest)
        assertEquals(0, panel.permissionsRowCountForTest)
    }

    fun `test analyze APK forwards an explicit on demand intent`() {
        val intents = mutableListOf<AppDetailsIntent>()
        val panel = AppDetailsPanel { intents += it }
        panel.update(state())

        panel.analyzeDeepLinksForTest.doClick()

        assertTrue(intents.contains(AppDetailsIntent.AnalyzeDeepLinks))
    }

    fun `test deep link rows expose source badges runtime uncertainty and stale validation error`() {
        val panel = AppDetailsPanel {}
        val target = DeepLinkTarget(
            "com.acme.shop.Main",
            DeepLinkTargetKind.ACTIVITY,
            patterns = listOf(UriPattern(schemes = setOf("https"), hosts = setOf("example.com"))),
            hasDefaultCategory = true,
            sources = setOf(DeepLinkSource.PROJECT, DeepLinkSource.RUNTIME_UNKNOWN),
        )
        panel.update(state().copy(deepLinks = DeepLinkAnalysis(
            DeepLinkCatalog("com.acme.shop", listOf(target)),
            verifications = listOf(AppLinkVerification("example.com", DeviceLinkState.VERIFIED,
                remoteValid = true, validatedAtEpochMillis = 1234L, stale = true, error = "offline")),
        )))

        assertEquals("Project · Runtime unknown", panel.deepLinksValueForTest(0, 3))
        assertTrue(panel.deepLinksValueForTest(0, 5).contains("Stale validation"))
        assertTrue(panel.deepLinksValueForTest(0, 5).contains("offline"))
    }
}
