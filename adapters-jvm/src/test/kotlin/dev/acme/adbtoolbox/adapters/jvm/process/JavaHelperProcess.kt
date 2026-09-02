package dev.acme.adbtoolbox.adapters.jvm.process

import dev.acme.adbtoolbox.domain.process.ProcessCommand

/**
 * Builds a [ProcessCommand] that re-invokes the current JDK to run [HelperProcessMain] on the
 * test classpath. Using the JDK we are already running under keeps integration tests fully
 * deterministic and cross-platform without depending on `adb`, a shell, or any other external
 * binary.
 */
internal object JavaHelperProcess {
    private val javaBinary: String =
        System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java"
    private val classpath: String = System.getProperty("java.class.path")

    fun command(vararg mode: String): ProcessCommand = ProcessCommand(
        executable = javaBinary,
        arguments = listOf("-cp", classpath, HelperProcessMain::class.java.name) + mode,
    )
}
