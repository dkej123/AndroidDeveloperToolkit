package dev.acme.adbtoolbox.domain.process

/**
 * Whether a process's stdout/stderr should be decoded as incremental UTF-8 text (line events) or
 * passed through as raw, binary-safe byte chunks. A single request never mixes the two — text and
 * binary output must never be conflated.
 */
sealed interface ProcessOutputKind {
    data object Text : ProcessOutputKind

    data object Binary : ProcessOutputKind
}
