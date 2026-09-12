package dev.acme.adbtoolbox.domain.capture

import dev.acme.adbtoolbox.domain.process.ByteSink

/**
 * Platform-neutral capture-destination port (task 019): resolves a collision-safe target for a
 * [CaptureDestination.beginCapture] request and hands back a [CaptureTarget] the caller writes
 * bytes into. Implementations must guarantee that a name collision (a file already at the intended
 * path) is resolved to a different path before this returns — a second capture must never silently
 * overwrite the first.
 */
interface CaptureDestination {
    suspend fun beginCapture(baseFileName: String): CaptureTarget
}

/**
 * One in-flight capture's write target. [sink] receives raw bytes exactly as they arrive from the
 * transport — never buffered whole in memory and never text-decoded. [commit] atomically publishes
 * the captured bytes at their final location only once the whole capture has genuinely succeeded;
 * [discard] removes any partial data written so far. Implementations must make both idempotent so a
 * caller never has to track which one it already called.
 */
interface CaptureTarget {
    val sink: ByteSink

    suspend fun commit(): CaptureLocation

    suspend fun discard()
}

/** Where a committed capture ended up. [displayPath] is a human-readable full path for feedback text. */
data class CaptureLocation(val displayPath: String)
