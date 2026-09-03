package dev.acme.adbtoolbox.domain.logcat

/** The process ID a logcat record was emitted from. */
@JvmInline
value class ProcessId(val value: Int)

/** The thread ID a logcat record was emitted from. */
@JvmInline
value class ThreadId(val value: Int)

/** A logcat record's tag, as written by the emitting app/system component. */
@JvmInline
value class LogTag(val value: String)

/** A logcat record's message text, excluding any continuation lines that follow it. */
@JvmInline
value class LogMessage(val value: String)

/** One successfully parsed logcat threadtime header line. */
data class LogcatRecord(
    val timestamp: LogTimestamp,
    val pid: ProcessId,
    val tid: ThreadId,
    val severity: LogSeverity,
    val tag: LogTag,
    val message: LogMessage,
)
