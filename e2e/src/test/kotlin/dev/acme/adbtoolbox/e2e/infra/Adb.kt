package dev.acme.adbtoolbox.e2e.infra

import java.util.concurrent.TimeUnit

/**
 * Ground truth: the tests never trust the plugin's own UI for "did it work?" — every effect on the
 * device is re-read here with a plain adb invocation, independent of the plugin under test.
 */
object Adb {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun run(vararg args: String, timeoutSeconds: Long = 120): Result {
        val stderrFile = java.io.File.createTempFile("e2e-adb", ".err")
        try {
            val process = ProcessBuilder(listOf(E2eConfig.adb.path) + args)
                .redirectError(stderrFile)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "adb ${args.joinToString(" ")} timed out" }
            return Result(process.exitValue(), stdout.replace("\r", ""), stderrFile.readText().replace("\r", ""))
        } finally {
            stderrFile.delete()
        }
    }

    /** `adb -s <serial> shell <command>`, trimmed stdout. */
    fun shell(command: String, timeoutSeconds: Long = 120): String =
        run("-s", E2eConfig.serial, "shell", command, timeoutSeconds = timeoutSeconds).stdout.trim()

    fun setting(namespace: String, key: String): String = shell("settings get $namespace $key")

    fun putSetting(namespace: String, key: String, value: String) {
        shell("settings put $namespace $key $value")
    }

    fun deleteSetting(namespace: String, key: String) {
        shell("settings delete $namespace $key")
    }

    fun prop(name: String): String = shell("getprop $name")

    fun isInstalled(packageName: String): Boolean =
        shell("pm list packages $packageName").lines().any { it.trim() == "package:$packageName" }

    fun pidOf(packageName: String): String = shell("pidof $packageName")

    fun install(apk: java.io.File) {
        val result = run("-s", E2eConfig.serial, "install", "-r", apk.path, timeoutSeconds = 600)
        check(result.exitCode == 0 && "Success" in result.stdout) { "install ${apk.name} failed: $result" }
    }

    fun isBooted(): Boolean = runCatching { prop("sys.boot_completed") == "1" }.getOrDefault(false)

    /** Writes [count] lines to logcat from the device side as fast as the shell allows. */
    fun floodLogcat(count: Int, text: String) {
        shell(
            "logwrapper sh -c 'i=0; while [ \$i -lt $count ]; do echo $text \$i; i=\$((i+1)); done'",
            timeoutSeconds = 600,
        )
    }

    fun log(tag: String, message: String, priority: String = "i") {
        shell("log -p $priority -t $tag '$message'")
    }
}
