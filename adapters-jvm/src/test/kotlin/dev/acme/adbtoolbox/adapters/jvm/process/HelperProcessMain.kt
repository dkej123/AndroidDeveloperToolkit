package dev.acme.adbtoolbox.adapters.jvm.process

import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * A controlled child process used only by [JvmProcessExecutor] integration tests, spawned via the
 * current JDK on the test classpath so tests never depend on `adb`, a shell, or any external
 * binary. Deterministic, cross-platform behavior selected by `args[0]`.
 */
object HelperProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val stdout = PrintStream(System.out, true, Charsets.UTF_8)
        val stderr = PrintStream(System.err, true, Charsets.UTF_8)
        when (args.getOrNull(0)) {
            "exit-code" -> exitProcess(args[1].toInt())

            "stdout-stderr" -> {
                stdout.println("stdout-line-1")
                stderr.println("stderr-line-1")
                stdout.println("stdout-line-2")
                exitProcess(0)
            }

            "sleep" -> {
                Thread.sleep(args[1].toLong())
                exitProcess(0)
            }

            "binary" -> {
                // Non-UTF-8-safe bytes: a null byte and a lone continuation byte.
                val bytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x89.toByte(), 0x50, 0x4E, 0x47)
                System.out.write(bytes)
                System.out.flush()
                exitProcess(0)
            }

            "large-output" -> {
                val lineCount = args[1].toInt()
                repeat(lineCount) { i -> stdout.println("line-$i-".repeat(20)) }
                exitProcess(0)
            }

            "echo-args" -> {
                args.drop(1).forEach { stdout.println(it) }
                exitProcess(0)
            }

            else -> {
                stderr.println("unknown mode: ${args.getOrNull(0)}")
                exitProcess(2)
            }
        }
    }
}
