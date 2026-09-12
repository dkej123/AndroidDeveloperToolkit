package dev.acme.adbtoolbox.adapters.jvm.capture

import dev.acme.adbtoolbox.domain.capture.CaptureDestination
import dev.acme.adbtoolbox.domain.capture.CaptureLocation
import dev.acme.adbtoolbox.domain.capture.CaptureTarget
import dev.acme.adbtoolbox.domain.process.ByteSink
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The `:adapters-jvm` [CaptureDestination] (task 019): writes into a hidden temp file inside
 * [directory] and only [CaptureTarget.commit]s by an atomic [Files.move] onto the final,
 * collision-free path — a reader can never observe a partially-written capture at its final name,
 * and a failed/cancelled capture ([CaptureTarget.discard]) only ever deletes the temp file, never
 * touching a previously-committed one. [directory] defaults to `~/Desktop`
 * (`design/README.md`'s Capture section meta); task 038 owns making this user-configurable.
 */
class JvmCaptureDestination(
    private val directory: Path = Path.of(System.getProperty("user.home"), "Desktop"),
) : CaptureDestination {

    override suspend fun beginCapture(baseFileName: String): CaptureTarget = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val finalPath = collisionFreePath(baseFileName)
        val tempPath = Files.createTempFile(directory, ".capture-", ".tmp")
        JvmCaptureTarget(tempPath, finalPath)
    }

    private fun collisionFreePath(baseFileName: String): Path {
        val direct = directory.resolve(baseFileName)
        if (!Files.exists(direct)) return direct

        val dotIndex = baseFileName.lastIndexOf('.')
        val stem = if (dotIndex >= 0) baseFileName.substring(0, dotIndex) else baseFileName
        val extension = if (dotIndex >= 0) baseFileName.substring(dotIndex) else ""

        var counter = 1
        while (true) {
            val candidate = directory.resolve("$stem-$counter$extension")
            if (!Files.exists(candidate)) return candidate
            counter++
        }
    }
}

private class JvmCaptureTarget(
    private val tempPath: Path,
    private val finalPath: Path,
) : CaptureTarget {

    private val outputStream: OutputStream = Files.newOutputStream(tempPath)

    @Volatile
    private var finished = false

    override val sink = ByteSink { bytes -> outputStream.write(bytes) }

    override suspend fun commit(): CaptureLocation = withContext(Dispatchers.IO) {
        check(!finished) { "capture target already finished (committed or discarded)" }
        finished = true
        outputStream.flush()
        outputStream.close()
        Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE)
        CaptureLocation(displayPath = finalPath.toString())
    }

    override suspend fun discard() = withContext(Dispatchers.IO) {
        if (finished) return@withContext
        finished = true
        runCatching { outputStream.close() }
        Files.deleteIfExists(tempPath)
        Unit
    }
}
