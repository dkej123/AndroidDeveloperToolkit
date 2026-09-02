import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// JVM-only per docs/adr/0001: implements the :domain AdbTransport port (ddmlib and binary-adb
// adapters, ADR 0005) plus tool discovery, built on :adapters-jvm's process executor for the binary
// path. Depends on :domain and :adapters-jvm only.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":adapters-jvm"))
    implementation(libs.kotlinx.coroutines.core)

    // ddmlib (docs/adr/0005's preferred transport): a plain JVM library published by Google, not
    // an IntelliJ Platform API — safe to depend on directly here without pulling in
    // org.jetbrains.android or any com.intellij type (enforced by architectureCheck).
    //
    // compileOnly, not implementation: DdmlibAdbTransport is only ever constructed by the
    // `:intellij` composition root when the Android plugin is present (ADR 0005, task 007), and
    // that plugin bundles its own copy of ddmlib. If this module's own artifact also bundled
    // ddmlib into the packaged plugin, the JVM would load two distinct `AndroidDebugBridge`/
    // `IDevice` classes (ours vs. the Android plugin's) from two different plugin classloaders,
    // and `com.android.ddmlib.AndroidDebugBridge`'s static/shared state would never line up
    // between them. Staying compileOnly means the plugin never bundles a second copy, so at
    // runtime every `com.android.ddmlib.*` reference resolves to the single copy the Android
    // plugin provides via IntelliJ's plugin-dependency classloader delegation.
    compileOnly(libs.ddmlib)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // Tests exercise DdmlibAdbTransport directly against real ddmlib types (mocked via mockk), so
    // the test source set needs ddmlib on its own compile+runtime classpath even though main does
    // not (see the compileOnly comment above).
    testImplementation(libs.ddmlib)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()

    // Forwards the opt-in local discovery smoke test's gate (tasks/004-tool-discovery.md
    // Validation) from the Gradle daemon's `-D` into the forked test JVM, which does not inherit
    // arbitrary system properties automatically. Disabled unless explicitly passed.
    System.getProperty("adbToolbox.discoverySmokeTest")?.let {
        systemProperty("adbToolbox.discoverySmokeTest", it)
    }
}
