package dev.acme.adbtoolbox.adapters.jvm.deeplinks

import dev.acme.adbtoolbox.domain.adb.AdbBinaryScript
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.adb.FakeAdbTransport
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkAnalysisResult
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkCatalog
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkSource
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkTarget
import dev.acme.adbtoolbox.domain.deeplinks.DeepLinkTargetKind
import dev.acme.adbtoolbox.domain.deeplinks.AssetLinksFetchResult
import dev.acme.adbtoolbox.domain.deeplinks.AssetLinksFetcher
import dev.acme.adbtoolbox.domain.deeplinks.ProjectDeepLinkProvider
import dev.acme.adbtoolbox.domain.deeplinks.UriPattern
import dev.acme.adbtoolbox.domain.process.FakeProcessExecutor
import dev.acme.adbtoolbox.domain.process.ProcessEvent
import dev.acme.adbtoolbox.domain.process.ProcessOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JvmApkDeepLinkAnalyzerTest {
    @TempDir lateinit var cache: Path

    @Test
    fun `pulls every apk with argv and cache hit performs no adb or process call`() = runTest {
        val manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="p"><application><activity android:name=".A" android:exported="true"><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="x"/></intent-filter></activity></application></manifest>"""
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), "package:/data/app/p/base.apk\npackage:/data/app/p/split_config.apk\n", "") },
            binaryScript = { AdbBinaryScript(listOf("apk".toByteArray()), AdbOutcome.Completed(0)) },
        )
        val processes = FakeProcessExecutor { request ->
            if (request.command.executable == "/sdk/apkanalyzer") manifest.lines().map(ProcessEvent::StdoutText) + ProcessEvent.Completed(ProcessOutcome.Completed(0))
            else listOf(ProcessEvent.StdoutText("Signer #1 certificate SHA-256 digest: AA:BB"), ProcessEvent.Completed(ProcessOutcome.Completed(0)))
        }
        val analyzer = JvmApkDeepLinkAnalyzer(transport, processes, "/sdk/apkanalyzer", "/sdk/apksigner", cache, "project")
        val serial = DeviceSerial.of("serial")

        analyzer.analyze(serial, 10, "p", "1", "now", "/data/app/p", refresh = true)
            .shouldBeInstanceOf<DeepLinkAnalysisResult.Success>().analysis.catalog.targets.size shouldBe 1
        (transport.textRequests.first() as AdbDeviceRequest).let {
            (it.operation as AdbOperation.Shell).command.render() shouldBe "pm path --user '10' 'p'"
        }
        transport.binaryRequests.map { (it as AdbDeviceRequest).operation } shouldBe listOf(
            AdbOperation.Exec(listOf("cat", "/data/app/p/base.apk")),
            AdbOperation.Exec(listOf("cat", "/data/app/p/split_config.apk")),
        )
        processes.requests.take(2).map { it.command.arguments.take(2) } shouldBe listOf(
            listOf("manifest", "print"), listOf("manifest", "print"),
        )
        val adbCalls = transport.textRequests.size + transport.binaryRequests.size
        val processCalls = processes.requests.size

        analyzer.analyze(serial, 10, "p", "1", "now", "/data/app/p", refresh = false)
            .shouldBeInstanceOf<DeepLinkAnalysisResult.Success>().analysis.fromCache shouldBe true
        transport.textRequests.size + transport.binaryRequests.size shouldBe adbCalls
        processes.requests.size shouldBe processCalls
    }

    @Test
    fun `applies matching active-project metadata to fresh and cached APK catalogs`() = runTest {
        val manifest = """<manifest package="p"><application><activity android:name=".A" android:exported="true"><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="x"/></intent-filter></activity></application></manifest>"""
        val transport = FakeAdbTransport(
            textScript = { AdbTextResult(AdbOutcome.Completed(0), "package:/data/app/p/base.apk\n", "") },
            binaryScript = { AdbBinaryScript(listOf("apk".toByteArray()), AdbOutcome.Completed(0)) },
        )
        val processes = FakeProcessExecutor { listOf(ProcessEvent.StdoutText(manifest), ProcessEvent.Completed(ProcessOutcome.Completed(0))) }
        val project = ProjectDeepLinkProvider { packageName ->
            DeepLinkCatalog(packageName, listOf(DeepLinkTarget("p.A", DeepLinkTargetKind.ACTIVITY,
                patterns = listOf(UriPattern(schemes = setOf("x"))), hasDefaultCategory = false,
                sources = setOf(DeepLinkSource.PROJECT))))
        }
        val analyzer = JvmApkDeepLinkAnalyzer(transport, processes, "/sdk/apkanalyzer", null, cache, "project",
            projectDeepLinks = project)
        val serial = DeviceSerial.of("serial")

        repeat(2) { index ->
            val analysis = analyzer.analyze(serial, 0, "p", "1", null, null, refresh = index == 0)
                .shouldBeInstanceOf<DeepLinkAnalysisResult.Success>().analysis
            analysis.catalog.targets.single().sources shouldBe setOf(DeepLinkSource.APK, DeepLinkSource.PROJECT)
        }
    }

    @Test
    fun `keeps last good remote validation stale with timestamp and latest network error`() = runTest {
        val manifest = """<manifest package="p"><application><activity android:name=".A" android:exported="true"><intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="https" android:host="example.com"/></intent-filter></activity></application></manifest>"""
        val transport = FakeAdbTransport(
            textScript = { request ->
                val command = ((request as AdbDeviceRequest).operation as AdbOperation.Shell).command.render()
                AdbTextResult(AdbOutcome.Completed(0), if (command.startsWith("pm path")) "package:/data/app/p/base.apk\n" else "", "")
            },
            binaryScript = { AdbBinaryScript(listOf("apk".toByteArray()), AdbOutcome.Completed(0)) },
        )
        val processes = FakeProcessExecutor { request ->
            val text = if (request.command.executable.endsWith("apksigner")) {
                "Signer #1 certificate SHA-256 digest: AA:BB"
            } else manifest
            listOf(ProcessEvent.StdoutText(text), ProcessEvent.Completed(ProcessOutcome.Completed(0)))
        }
        var fetch: AssetLinksFetchResult = AssetLinksFetchResult.Success("""[{
          "relation":["delegate_permission/common.handle_all_urls"],
          "target":{"namespace":"android_app","package_name":"p","sha256_cert_fingerprints":["AA:BB"]}
        }]""")
        val analyzer = JvmApkDeepLinkAnalyzer(transport, processes, "/sdk/apkanalyzer", "/sdk/apksigner", cache, "project",
            assetLinksFetcher = AssetLinksFetcher { fetch }, clockMillis = { 1234L })
        val serial = DeviceSerial.of("serial")

        analyzer.analyze(serial, 0, "p", "1", null, null, refresh = true)
            .shouldBeInstanceOf<DeepLinkAnalysisResult.Success>().analysis.verifications.single().remoteValid shouldBe true
        fetch = AssetLinksFetchResult.Failure("offline")
        val stale = analyzer.analyze(serial, 0, "p", "1", null, null, refresh = true)
            .shouldBeInstanceOf<DeepLinkAnalysisResult.Success>().analysis.verifications.single()
        stale.remoteValid shouldBe true
        stale.stale shouldBe true
        stale.validatedAtEpochMillis shouldBe 1234L
        stale.error shouldBe "offline"

        val restored = analyzer.analyze(serial, 0, "p", "1", null, null, refresh = false)
            .shouldBeInstanceOf<DeepLinkAnalysisResult.Success>().analysis.verifications.single()
        restored.stale shouldBe true
        restored.validatedAtEpochMillis shouldBe 1234L
        restored.error shouldBe "offline"
    }
}
