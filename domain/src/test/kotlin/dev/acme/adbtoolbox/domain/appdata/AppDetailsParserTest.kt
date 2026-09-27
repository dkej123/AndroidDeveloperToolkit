package dev.acme.adbtoolbox.domain.appdata

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val DUMP = """
Packages:
  Package [com.acme.shop] (3f2a9c1):
    userId=10081
    pkg=Package{d1 com.acme.shop}
    codePath=/data/app/com.acme.shop-kVEE==
    primaryCpuAbi=arm64-v8a
    versionCode=47 minSdk=23 targetSdk=34
    versionName=2.27
    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
    dataDir=/data/user/0/com.acme.shop
    timeStamp=2026-09-24 20:32:42
    firstInstallTime=2026-09-24 20:32:42
    lastUpdateTime=2026-09-25 09:00:00
    installerPackageName=com.android.vending
    requested permissions:
      android.permission.INTERNET
      android.permission.CAMERA
    install permissions:
      android.permission.INTERNET: granted=true
    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=true notLaunched=false enabled=0
      gids=[3003]
      runtime permissions:
        android.permission.CAMERA: granted=false, flags=[ USER_SET ]
        android.permission.POST_NOTIFICATIONS: granted=true

Package Changes:
  Sequence number=10
"""

class AppDetailsParserTest {

    @Test
    fun `parses identity, versions, paths and install info`() {
        val details = AppDetailsParser.parse("com.acme.shop", DUMP)

        details.versionName shouldBe "2.27"
        details.versionCode shouldBe "47"
        details.minSdk shouldBe "23"
        details.targetSdk shouldBe "34"
        details.uid shouldBe "10081"
        details.codePath shouldBe "/data/app/com.acme.shop-kVEE=="
        details.dataDir shouldBe "/data/user/0/com.acme.shop"
        details.firstInstallTime shouldBe "2026-09-24 20:32:42"
        details.lastUpdateTime shouldBe "2026-09-25 09:00:00"
        details.installer shouldBe "com.android.vending"
        details.primaryAbi shouldBe "arm64-v8a"
        details.isDebuggable shouldBe true
        details.isStopped shouldBe true
        details.flags shouldBe listOf("DEBUGGABLE", "HAS_CODE", "ALLOW_CLEAR_USER_DATA", "ALLOW_BACKUP")
    }

    @Test
    fun `permissions combine requested, install and runtime grants`() {
        AppDetailsParser.parse("com.acme.shop", DUMP).permissions shouldBe listOf(
            AppPermission("android.permission.CAMERA", granted = false, runtime = true),
            AppPermission("android.permission.INTERNET", granted = true, runtime = false),
            AppPermission("android.permission.POST_NOTIFICATIONS", granted = true, runtime = true),
        )
    }

    @Test
    fun `missing fields are null rather than guessed`() {
        val details = AppDetailsParser.parse("com.acme.shop", "Packages:\n  Package [com.acme.shop] (1):\n    versionName=1.0\n")

        details.versionName shouldBe "1.0"
        details.installer shouldBe null
        details.primaryAbi shouldBe null
        details.isDebuggable shouldBe false
        details.permissions shouldBe emptyList()
    }
}
