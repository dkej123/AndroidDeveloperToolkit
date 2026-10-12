package io.github.dkej123.devicecockpit.intellij.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalysisResult
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalyzer
import java.nio.file.Files
import java.nio.file.Path

internal const val APKANALYZER_MISSING =
    "apkanalyzer not found — install “Android SDK Command-line Tools” in Settings › Android SDK › SDK Tools"

/**
 * Finds apkanalyzer on each Analyze until it is there, then keeps it: the SDK or its command-line
 * tools may be installed while the IDE runs, and a lookup done once at startup would never see them.
 */
internal class LazyDeepLinkAnalyzer(private val resolve: () -> DeepLinkAnalyzer?) : DeepLinkAnalyzer {
    @Volatile private var resolved: DeepLinkAnalyzer? = null

    override suspend fun analyze(
        serial: DeviceSerial,
        androidUserId: Int,
        packageName: String,
        versionCode: String?,
        lastUpdateTime: String?,
        codePath: String?,
        refresh: Boolean,
    ): DeepLinkAnalysisResult {
        val analyzer = resolved ?: resolve()?.also { resolved = it } ?: return DeepLinkAnalysisResult.Failure(APKANALYZER_MISSING)
        return analyzer.analyze(serial, androidUserId, packageName, versionCode, lastUpdateTime, codePath, refresh)
    }
}

/** The first existing SDK directory among [candidates]. */
internal fun androidSdkDirectory(candidates: List<Path>): Path? = candidates.firstOrNull(Files::isDirectory)

/** Where Android Studio's setup wizard installs the SDK by default (the same places adb discovery checks). */
internal fun defaultSdkLocations(osName: String, userHome: String?, localAppData: String?): List<Path> = when {
    osName.startsWith("Mac") -> listOfNotNull(userHome?.let { Path.of(it, "Library", "Android", "sdk") })
    osName.startsWith("Windows") -> listOfNotNull(localAppData?.let { Path.of(it, "Android", "Sdk") })
    else -> listOfNotNull(userHome?.let { Path.of(it, "Android", "Sdk") })
}
