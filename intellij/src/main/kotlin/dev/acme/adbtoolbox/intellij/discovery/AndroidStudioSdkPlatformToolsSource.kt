package dev.acme.adbtoolbox.intellij.discovery

import com.intellij.openapi.application.readAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import dev.acme.adbtoolbox.domain.discovery.AndroidSdkPlatformToolsSource
import java.io.File

/**
 * Looks for an `platform-tools` directory under any SDK the IDE already knows about
 * ([ProjectJdkTable] — the generic IntelliJ Platform SDK table, not an Android-plugin-specific API,
 * since `:intellij` does not yet take a compile-time dependency on `org.jetbrains.android`; task 005
 * adds that when the ddmlib transport needs real Android SDK types). When Android Studio's Android
 * plugin has registered an Android SDK, its `homePath` is exactly the SDK root, so this heuristic
 * finds it without any Android-specific classes.
 *
 * A [dev.acme.adbtoolbox.domain.discovery.ToolLocator] composed with this source (task 007's
 * composition root) tries it alongside `:adapters-jvm`'s environment-variable-based source — this
 * one is the "ask the IDE directly" half of that tier, not the sole implementation of it.
 *
 * Reads the SDK table via [readAction] so it never touches IntelliJ's read/write lock from the EDT
 * caller's thread directly, and the filesystem probe of each candidate directory runs off the EDT
 * inside that same read action's background dispatch.
 */
class AndroidStudioSdkPlatformToolsSource : AndroidSdkPlatformToolsSource {
    override suspend fun platformToolsDirectory(): String? = readAction {
        ProjectJdkTable.getInstance().allJdks
            .asSequence()
            .mapNotNull { it.homePath }
            .map { homePath -> "$homePath/platform-tools" }
            .firstOrNull { candidate -> File(candidate).isDirectory }
    }
}
