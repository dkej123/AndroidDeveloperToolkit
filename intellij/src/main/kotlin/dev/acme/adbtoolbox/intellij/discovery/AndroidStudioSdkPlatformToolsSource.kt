package dev.acme.adbtoolbox.intellij.discovery

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.domain.discovery.AndroidSdkPlatformToolsSource
import org.jetbrains.android.sdk.AndroidSdkUtils
import java.io.File

/**
 * Resolves `platform-tools` from the exact adb executable selected by Android Studio. Android SDKs
 * are not guaranteed to appear in IntelliJ's generic JDK table, so deriving this from
 * `ProjectJdkTable` can report no adb while Device Manager is already connected to one.
 *
 * A [dev.acme.adbtoolbox.domain.discovery.ToolLocator] composed with this source (task 007's
 * composition root) tries it alongside `:adapters-jvm`'s environment-variable-based source — this
 * one is the "ask the IDE directly" half of that tier, not the sole implementation of it.
 *
 * Reads the SDK table via [readAction] so it never touches IntelliJ's read/write lock from the EDT
 * caller's thread directly, and the filesystem probe of each candidate directory runs off the EDT
 * inside that same read action's background dispatch.
 */
class AndroidStudioSdkPlatformToolsSource(
    private val adbPathProvider: () -> File?,
) : AndroidSdkPlatformToolsSource {

    constructor(project: Project) : this(adbPathProvider = { AndroidSdkUtils.getAdb(project) })

    override suspend fun platformToolsDirectory(): String? = readAction {
        platformToolsDirectory(adbPathProvider())
    }
}

internal fun platformToolsDirectory(adbPath: File?): String? = adbPath?.parentFile?.absolutePath
