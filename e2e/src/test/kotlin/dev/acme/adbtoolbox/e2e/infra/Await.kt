package dev.acme.adbtoolbox.e2e.infra

import com.intellij.remoterobot.utils.waitFor
import java.time.Duration

/** Polls [condition] every [interval] until it holds; the failure message names [what] was awaited. */
fun awaitUntil(timeout: Duration, interval: Duration, what: String, condition: () -> Boolean) =
    waitFor(timeout, interval, what, "timed out after $timeout waiting for $what", condition)
