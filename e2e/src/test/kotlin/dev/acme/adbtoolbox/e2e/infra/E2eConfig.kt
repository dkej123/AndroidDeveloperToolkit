package dev.acme.adbtoolbox.e2e.infra

import java.io.File
import java.time.Duration

/**
 * Where the running environment lives. Every value comes from the variables exported by
 * `e2e/scripts/lib.sh` (forwarded by the `e2eTest` Gradle task), with the same defaults.
 */
object E2eConfig {
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    val home: File = File(env("E2E_HOME") ?: "${System.getProperty("user.home")}/.cache/adb-toolbox-e2e")
    val robotUrl: String = "http://127.0.0.1:${env("E2E_ROBOT_PORT") ?: "8082"}"
    val serial: String = env("E2E_DEVICE_SERIAL") ?: "emulator-5554"
    val sdkRoot: File = File(env("ANDROID_SDK_ROOT") ?: File(home, "android-sdk").path)
    val studioHome: File = File(env("E2E_STUDIO_HOME") ?: File(home, "android-studio").path)
    val sandbox: File = File(env("E2E_SANDBOX") ?: File(home, "studio-sandbox").path)
    val fixtures: File = File(env("E2E_FIXTURES") ?: File(home, "fixtures").path)
    val reportDir: File = File(System.getProperty("e2e.reportDir") ?: "build/e2e-report").apply { mkdirs() }

    val pluginLog: File get() = File(sandbox, "log/adb-toolbox/adb-toolbox.log")
    val ideaLog: File get() = File(sandbox, "log/idea.log")
    val adb: File get() = File(sdkRoot, "platform-tools/adb")

    /**
     * Multiplies every device-bound wait. Software-emulated (no KVM) devices are ~5-10x slower than
     * a phone, so the default is 1 with KVM and 4 without; override with E2E_TIMEOUT_SCALE.
     */
    val timeoutScale: Double = env("E2E_TIMEOUT_SCALE")?.toDouble()
        ?: if (env("E2E_EMULATOR_ACCEL") == "off" || !File("/dev/kvm").exists()) 4.0 else 1.0

    fun deviceTimeout(seconds: Long): Duration = Duration.ofMillis((seconds * 1000 * timeoutScale).toLong())
}
