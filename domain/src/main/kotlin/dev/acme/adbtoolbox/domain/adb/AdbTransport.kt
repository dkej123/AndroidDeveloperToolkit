package dev.acme.adbtoolbox.domain.adb

import dev.acme.adbtoolbox.domain.process.ByteSink
import kotlinx.coroutines.flow.Flow

/**
 * The single ADB gateway port (ADR 0005): implemented by exactly two `:adapters-adb` adapters
 * (ddmlib, binary) selected once per session at the `:intellij` composition root — no other module
 * invokes ddmlib or a raw `adb` process directly. Every feature-local command factory (Apps,
 * Display, Network, Capture, Logcat) builds [AdbRequest] values and calls through this port rather
 * than editing a shared command file.
 *
 * Three shapes cover every capability this project needs, matching ADR 0005's capability rules:
 * - [executeText] — a bounded, one-shot call returning a fully-collected [AdbTextResult] (e.g.
 *   `getprop`, `dumpsys battery`, `settings put/get`).
 * - [executeStream] — a long-running, cancellable line stream (e.g. `logcat -v threadtime`); the
 *   returned [Flow] is cold, and cancelling collection must tear down the underlying
 *   process/receiver.
 * - [executeBinary] — binary-safe output (e.g. `exec-out screencap -p`) streamed into [ByteSink]
 *   without buffering the whole payload in memory; text and binary output are never conflated.
 */
interface AdbTransport {
    suspend fun executeText(request: AdbRequest): AdbTextResult

    fun executeStream(request: AdbRequest): Flow<AdbStreamEvent>

    suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome
}
