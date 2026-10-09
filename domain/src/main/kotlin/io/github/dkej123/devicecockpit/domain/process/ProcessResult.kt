package io.github.dkej123.devicecockpit.domain.process

/** The bounded, fully-collected result of a [ProcessOutputKind.Text] execution. */
data class ProcessResult(
    val outcome: ProcessOutcome,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)
