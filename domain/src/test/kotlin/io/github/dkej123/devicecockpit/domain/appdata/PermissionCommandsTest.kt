package io.github.dkej123.devicecockpit.domain.appdata

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PermissionCommandsTest {
    private val serial = DeviceSerial.of("serial")

    @Test
    fun `grant revoke and reset use argv-safe shell commands and current user`() {
        fun render(request: AdbDeviceRequest) = (request.operation as AdbOperation.Shell).command.render()
        render(PermissionCommands.grant(serial, "com.acme.app", "android.permission.CAMERA", 12)) shouldBe
            "pm grant --user '12' 'com.acme.app' 'android.permission.CAMERA'"
        render(PermissionCommands.revoke(serial, "com.acme.app", "android.permission.CAMERA", 12)) shouldBe
            "pm revoke --user '12' 'com.acme.app' 'android.permission.CAMERA'"
        PermissionCommands.reset(serial, "com.acme.app", "android.permission.CAMERA", 12).map(::render) shouldBe listOf(
            "pm revoke --user '12' 'com.acme.app' 'android.permission.CAMERA'",
            "pm clear-permission-flags --user '12' 'com.acme.app' 'android.permission.CAMERA' 'user-set' 'user-fixed'",
        )
    }

    @Test
    fun `invalid identifiers are rejected before command construction`() {
        runCatching { PermissionCommands.grant(serial, "com.acme.app; id", "android.permission.CAMERA", 0) }.isFailure shouldBe true
        runCatching { PermissionCommands.grant(serial, "com.acme.app", "CAMERA\nreboot", 0) }.isFailure shouldBe true
    }

    @Test
    fun `dangerous permission catalog command and parser handle grouped platform output`() {
        val request = PermissionCommands.listDangerous(serial)
        (request.operation as AdbOperation.Shell).command.render() shouldBe "pm list permissions -g -d"
        PermissionCommands.parseDangerous("""
            Dangerous Permissions:
            group:android.permission-group.CAMERA
              permission:android.permission.CAMERA
              permission:com.acme.CUSTOM_DANGEROUS
        """.trimIndent()) shouldBe setOf("android.permission.CAMERA", "com.acme.CUSTOM_DANGEROUS")
    }
}
