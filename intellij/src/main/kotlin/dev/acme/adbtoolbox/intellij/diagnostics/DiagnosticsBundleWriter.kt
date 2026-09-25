package dev.acme.adbtoolbox.intellij.diagnostics

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** One item in the diagnostics ZIP; [name] is its path inside the archive. */
sealed interface BundleEntry {
    val name: String

    data class Text(override val name: String, val content: String) : BundleEntry

    /** Produced while writing; a failure is written into the entry instead of aborting the bundle. */
    class Lazy(override val name: String, val producer: () -> String) : BundleEntry

    data class FileCopy(override val name: String, val source: Path) : BundleEntry

    /** Only the last [maxBytes] of [source] — e.g. idea.log, which can be hundreds of MB. */
    data class FileTail(override val name: String, val source: Path, val maxBytes: Long) : BundleEntry

    /** Every regular file under [source], recursively, placed under [name]. */
    data class Directory(override val name: String, val source: Path) : BundleEntry
}

/** Writes [BundleEntry]s into one ZIP. Missing sources are skipped; nothing here needs the EDT. */
object DiagnosticsBundleWriter {

    fun write(target: Path, entries: List<BundleEntry>): Path {
        target.parent?.let(Files::createDirectories)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            entries.forEach { entry -> writeEntry(zip, entry) }
        }
        return target
    }

    private fun writeEntry(zip: ZipOutputStream, entry: BundleEntry) {
        when (entry) {
            is BundleEntry.Text -> putText(zip, entry.name, entry.content)
            is BundleEntry.Lazy -> putText(
                zip,
                entry.name,
                runCatching(entry.producer).getOrElse { failure -> "Could not collect ${entry.name}: ${failure.stackTraceToString()}" },
            )
            is BundleEntry.FileCopy -> if (entry.source.isRegularFile()) {
                put(zip, entry.name) { out -> Files.copy(entry.source, out) }
            }
            is BundleEntry.FileTail -> if (entry.source.isRegularFile()) {
                put(zip, entry.name) { out -> copyTail(entry.source, entry.maxBytes, out) }
            }
            is BundleEntry.Directory -> if (entry.source.isDirectory()) {
                Files.walk(entry.source).use { paths ->
                    paths.filter { it.isRegularFile() }.sorted().forEach { file ->
                        val relative = entry.source.relativize(file).joinToString("/")
                        put(zip, "${entry.name}/$relative") { out -> Files.copy(file, out) }
                    }
                }
            }
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, content: String) =
        put(zip, name) { out -> out.write(content.toByteArray(Charsets.UTF_8)) }

    private inline fun put(zip: ZipOutputStream, name: String, body: (OutputStream) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        body(zip)
        zip.closeEntry()
    }

    private fun copyTail(source: Path, maxBytes: Long, out: OutputStream) {
        Files.newByteChannel(source, StandardOpenOption.READ).use { channel ->
            channel.position((channel.size() - maxBytes).coerceAtLeast(0))
            val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
            while (channel.read(buffer) > 0) {
                buffer.flip()
                out.write(buffer.array(), 0, buffer.limit())
                buffer.clear()
            }
        }
    }
}
