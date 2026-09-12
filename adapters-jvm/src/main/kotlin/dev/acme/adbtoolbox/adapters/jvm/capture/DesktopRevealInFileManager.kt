package dev.acme.adbtoolbox.adapters.jvm.capture

import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import java.awt.Desktop
import java.nio.file.Path

/**
 * The `:adapters-jvm` [RevealInFileManager] (task 019): opens [CaptureLocation.displayPath]'s
 * containing folder in the host OS file manager (Finder/Explorer/other) via [java.awt.Desktop] — a
 * plain JVM API, not an IntelliJ Platform one, so this stays testable without an IDE fixture. A host
 * without desktop/`OPEN` support (e.g. a headless CI box) is a silently accepted no-op rather than a
 * crash — there is nothing actionable a user could do about a missing OS file manager.
 */
class DesktopRevealInFileManager : RevealInFileManager {

    override fun reveal(location: CaptureLocation) {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.OPEN)) return
        val parent = containingFolderOf(location) ?: return
        desktop.open(parent.toFile())
    }
}

/**
 * The pure "which folder would be opened" half of [DesktopRevealInFileManager.reveal], split out so
 * it is unit-testable without ever invoking the real [Desktop] API (which would pop a real Finder/
 * Explorer window during a test run — adb-development's "normal tests use no ... Finder/Explorer").
 */
internal fun containingFolderOf(location: CaptureLocation): Path? =
    Path.of(location.displayPath).toAbsolutePath().parent

