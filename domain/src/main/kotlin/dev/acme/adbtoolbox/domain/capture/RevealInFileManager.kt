package dev.acme.adbtoolbox.domain.capture

/**
 * Opens the host OS file manager (Finder/Explorer/other) with a captured file's containing folder
 * shown. Only ever invoked once a [CaptureLocation] genuinely exists on disk (task 019's "Reveal"
 * action is offered only after a valid local file exists) — this port itself does not re-validate
 * that, since revalidating a path a caller just committed would be redundant filesystem I/O on
 * every reveal click.
 */
fun interface RevealInFileManager {
    fun reveal(location: CaptureLocation)
}
