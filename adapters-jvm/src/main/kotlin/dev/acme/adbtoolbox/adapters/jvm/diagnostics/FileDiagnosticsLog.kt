package dev.acme.adbtoolbox.adapters.jvm.diagnostics

import dev.acme.adbtoolbox.domain.diagnostics.DiagLevel
import dev.acme.adbtoolbox.domain.diagnostics.DiagnosticsLog
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

const val DIAGNOSTICS_LOG_FILE_NAME = "adb-toolbox.log"

/**
 * The [DiagnosticsLog] file adapter: one line per entry in [directory]/`adb-toolbox.log`,
 * size-rotated to `.1` … `.(maxFiles-1)`.
 *
 * Callers never block and never touch the disk: each entry is formatted on the calling thread and
 * offered to a bounded queue drained by a single daemon writer thread. When the queue is full the
 * entry is dropped and counted; the writer reports the count as one `dropped entries` WARN line.
 * [mirror] receives WARN/ERROR entries too (the IntelliJ adapter forwards them to idea.log).
 */
class FileDiagnosticsLog(
    private val directory: Path,
    private val maxFileBytes: Long = 5L * 1024 * 1024,
    private val maxFiles: Int = 5,
    @Volatile var minLevel: DiagLevel = DiagLevel.INFO,
    queueCapacity: Int = 10_000,
    private val clock: () -> Instant = Instant::now,
    startWriter: Boolean = true,
    private val mirror: ((DiagLevel, String, Throwable?) -> Unit)? = null,
) : DiagnosticsLog {

    private sealed interface Item {
        class Line(val text: String) : Item
        class Flush(val done: CountDownLatch) : Item
        data object Stop : Item
    }

    private val queue = ArrayBlockingQueue<Item>(queueCapacity)
    private val dropped = AtomicLong()
    private val writerThread = Thread(::drain, "adb-toolbox-diagnostics-writer").apply { isDaemon = true }

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    private var out: BufferedWriter? = null
    private var currentSize = 0L

    val logFile: Path get() = directory.resolve(DIAGNOSTICS_LOG_FILE_NAME)

    init {
        require(maxFiles >= 1) { "maxFiles must be at least 1" }
        if (startWriter) startWriter()
    }

    @Synchronized
    fun startWriter() {
        if (started) return
        started = true
        writerThread.start()
    }

    override fun isEnabled(level: DiagLevel): Boolean = !closed && level >= minLevel

    override fun log(level: DiagLevel, category: String, message: String, fields: Map<String, Any?>, error: Throwable?) {
        if (!isEnabled(level)) return
        val text = format(level, category, message, fields, error)
        if (level >= DiagLevel.WARN) mirror?.invoke(level, text.substringBefore('\n'), error)
        if (!queue.offer(Item.Line(text))) dropped.incrementAndGet()
    }

    /** Blocks (up to [timeoutMs]) until everything logged so far is on disk — used before export. */
    fun flush(timeoutMs: Long = 5_000) {
        if (closed) return
        startWriter()
        val done = CountDownLatch(1)
        if (queue.offer(Item.Flush(done), timeoutMs, TimeUnit.MILLISECONDS)) done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Writes everything still queued, closes the file and stops the writer thread. */
    fun close(timeoutMs: Long = 5_000) {
        if (closed) return
        startWriter()
        queue.offer(Item.Stop, timeoutMs, TimeUnit.MILLISECONDS)
        writerThread.join(timeoutMs)
        closed = true
    }

    /** All log files, newest first — what the diagnostics bundle collects. */
    fun files(): List<Path> = (listOf(logFile) + (1 until maxFiles).map { directory.resolve("$DIAGNOSTICS_LOG_FILE_NAME.$it") })
        .filter(Files::exists)

    private fun drain() {
        try {
            while (true) {
                when (val item = queue.take()) {
                    is Item.Line -> {
                        write(item.text)
                        reportDropped()
                    }
                    is Item.Flush -> {
                        reportDropped()
                        out?.flush()
                        item.done.countDown()
                    }
                    Item.Stop -> {
                        reportDropped()
                        out?.flush()
                        out?.close()
                        out = null
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            out?.close()
        }
    }

    private fun reportDropped() {
        val count = dropped.getAndSet(0)
        if (count > 0) {
            write(format(DiagLevel.WARN, "diagnostics", "dropped entries", mapOf("count" to count, "reason" to "queue full"), null))
        }
    }

    private fun write(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8).size + 1L
        val writer = out ?: open()
        if (currentSize > 0 && currentSize + bytes > maxFileBytes) {
            rotate()
        }
        (out ?: writer).apply {
            write(text)
            newLine()
        }
        currentSize += bytes
        if (queue.isEmpty()) out?.flush()
    }

    private fun open(): BufferedWriter {
        Files.createDirectories(directory)
        currentSize = if (Files.exists(logFile)) Files.size(logFile) else 0L
        return Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            .also { out = it }
    }

    private fun rotate() {
        out?.close()
        out = null
        if (maxFiles == 1) {
            Files.deleteIfExists(logFile)
        } else {
            Files.deleteIfExists(directory.resolve("$DIAGNOSTICS_LOG_FILE_NAME.${maxFiles - 1}"))
            for (index in maxFiles - 2 downTo 1) {
                val source = directory.resolve("$DIAGNOSTICS_LOG_FILE_NAME.$index")
                if (Files.exists(source)) {
                    Files.move(source, directory.resolve("$DIAGNOSTICS_LOG_FILE_NAME.${index + 1}"), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            Files.move(logFile, directory.resolve("$DIAGNOSTICS_LOG_FILE_NAME.1"), StandardCopyOption.REPLACE_EXISTING)
        }
        open()
    }

    private fun format(level: DiagLevel, category: String, message: String, fields: Map<String, Any?>, error: Throwable?): String =
        buildString {
            append(clock()).append(' ')
            append(level.name.padEnd(5)).append(' ')
            append('[').append(category).append("] ")
            append(message)
            fields.forEach { (key, value) ->
                if (value != null) append(' ').append(key).append('=').append(renderValue(value.toString()))
            }
            append(" thread=").append(renderValue(Thread.currentThread().name))
            if (error != null) {
                append(" error=").append(error.toString().replace("\n", "\\n"))
                error.stackTrace.take(40).forEach { append("\n    at ").append(it) }
                error.cause?.let { cause -> append("\n    caused by ").append(cause) }
            }
        }

    private fun renderValue(raw: String): String {
        val needsQuotes = raw.isEmpty() || raw.any { it == ' ' || it == '"' || it == '=' || it == '\n' || it == '\r' || it == '\t' }
        if (!needsQuotes) return raw
        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }
}
