package io.github.dkej123.devicecockpit.intellij.apps.details

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dkej123.devicecockpit.application.appdetails.AppDetailsIntent
import io.github.dkej123.devicecockpit.domain.appdata.AppPermission
import io.github.dkej123.devicecockpit.domain.appdata.PermissionFlag
import io.github.dkej123.devicecockpit.domain.appdata.PermissionKind
import io.github.dkej123.devicecockpit.domain.appdata.PermissionState

class PermissionsListTest : BasePlatformTestCase() {

    private val camera = AppPermission("android.permission.CAMERA", PermissionKind.RUNTIME, PermissionState.GRANTED, setOf(PermissionFlag.USER_SET))
    private val location = AppPermission("android.permission.ACCESS_FINE_LOCATION", PermissionKind.RUNTIME, PermissionState.DENIED)
    private val contacts = AppPermission("android.permission.READ_CONTACTS", PermissionKind.RUNTIME, PermissionState.GRANTED, setOf(PermissionFlag.SYSTEM_FIXED))
    private val internet = AppPermission("android.permission.INTERNET", PermissionKind.INSTALL, PermissionState.GRANTED)
    private val wakeLock = AppPermission("android.permission.WAKE_LOCK", PermissionKind.INSTALL, PermissionState.GRANTED)

    private val all = listOf(internet, camera, wakeLock, location, contacts)

    fun `test runtime permissions come first and install-time ones fold into a read-only summary`() {
        val list = PermissionsList {}

        list.render(all, busy = null)

        assertEquals(listOf(camera.name, location.name, contacts.name), list.visibleRowsForTest)
        assertEquals("Runtime · 3", list.runtimeHeaderForTest)
        assertEquals("Install-time · 2 granted · read-only", list.installHeaderForTest)
        assertEquals("Camera", list.shortNameForTest(camera.name))
        assertEquals("Access fine location", list.shortNameForTest(location.name))

        list.toggleInstallForTest()

        assertEquals(listOf(camera.name, location.name, contacts.name, internet.name, wakeLock.name), list.visibleRowsForTest)
    }

    fun `test each row offers the action its state allows`() {
        val sent = mutableListOf<AppDetailsIntent>()
        val list = PermissionsList { sent += it }
        list.render(all, busy = null)

        assertEquals("Revoke", list.actionForTest(camera.name)?.text)
        assertEquals("Grant", list.actionForTest(location.name)?.text)
        assertNull(list.actionForTest(contacts.name))
        assertEquals("Granted", list.stateForTest(contacts.name))
        assertEquals("Denied", list.stateForTest(location.name))
        assertEquals(listOf("system"), list.flagsForTest(contacts.name))
        assertEquals(listOf("user-set"), list.flagsForTest(camera.name))

        list.actionForTest(camera.name)!!.doClick()
        list.actionForTest(location.name)!!.doClick()
        list.resetForTest(camera.name)!!.doClick()

        assertEquals(
            listOf(
                AppDetailsIntent.RevokePermission(camera.name),
                AppDetailsIntent.GrantPermission(location.name),
                AppDetailsIntent.ResetPermission(camera.name),
            ),
            sent,
        )
        assertFalse(list.resetForTest(location.name)!!.isEnabled)
    }

    fun `test the filter matches short and full names in both groups`() {
        val list = PermissionsList {}
        list.render(all, busy = null)

        list.filterForTest = "lock"
        assertEquals(listOf(wakeLock.name), list.visibleRowsForTest)

        list.filterForTest = "fine loc"
        assertEquals(listOf(location.name), list.visibleRowsForTest)

        list.filterForTest = ""
        assertEquals(listOf(camera.name, location.name, contacts.name), list.visibleRowsForTest)
    }

    fun `test reset all is offered only while something can be reset and nothing is applying`() {
        val sent = mutableListOf<AppDetailsIntent>()
        val list = PermissionsList { sent += it }

        list.render(listOf(location, internet), busy = null)
        assertFalse(list.resetAllForTest.isEnabled)

        list.render(all, busy = null)
        assertTrue(list.resetAllForTest.isEnabled)
        list.resetAllForTest.doClick()
        assertEquals(listOf<AppDetailsIntent>(AppDetailsIntent.ResetAllPermissions), sent)

        list.render(all, busy = camera.name)
        assertFalse(list.resetAllForTest.isEnabled)
        assertFalse(list.actionForTest(location.name)!!.isEnabled)
    }

    fun `test an app without permissions says so`() {
        val list = PermissionsList {}

        list.render(emptyList(), busy = null)

        assertEquals(emptyList<String>(), list.visibleRowsForTest)
        assertTrue(list.emptyTextForTest.isNotEmpty())
    }
}
