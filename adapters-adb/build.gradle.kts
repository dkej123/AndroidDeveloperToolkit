import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// JVM-only per docs/adr/0001: implements the :domain AdbTransport port (ddmlib and binary-adb
// adapters, ADR 0005) plus tool discovery, built on :adapters-jvm's process executor for the binary
// path. Depends on :domain and :adapters-jvm only.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

// The on-device app-info helper (ADR 0007): plain Java run on the device by `app_process`, compiled
// against compile-only stubs of the few android.* classes it touches (the device supplies the real
// ones) and dexed by D8, so building it needs no Android SDK. The dexed jar ships as a resource of
// this module and is pushed to the device on first use.
val deviceHelperStubs: SourceSet by sourceSets.creating
val deviceHelper: SourceSet by sourceSets.creating {
    compileClasspath += deviceHelperStubs.output
}
val d8: Configuration by configurations.creating

tasks.named<JavaCompile>(deviceHelperStubs.compileJavaTaskName) {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}
tasks.named<JavaCompile>(deviceHelper.compileJavaTaskName) {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

val deviceHelperResources = layout.buildDirectory.dir("generated/deviceHelper/resources")
val dexDeviceHelper = tasks.register<JavaExec>("dexDeviceHelper") {
    description = "Dexes the on-device app-info helper into a resource jar."
    val classes = deviceHelper.output.classesDirs
    val output = deviceHelperResources.map { it.file("dev/acme/adbtoolbox/adapters/adb/apps/app-info-helper.jar") }
    inputs.files(classes)
    outputs.file(output)
    classpath = d8
    mainClass.set("com.android.tools.r8.D8")
    argumentProviders += CommandLineArgumentProvider {
        listOf("--release", "--min-api", "21", "--output", output.get().asFile.absolutePath) +
            classes.asFileTree.matching { include("**/*.class") }.files.map { it.absolutePath }.sorted()
    }
    doFirst { output.get().asFile.parentFile.mkdirs() }
}
sourceSets.main {
    resources.srcDir(files(deviceHelperResources).builtBy(dexDeviceHelper))
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

    d8(libs.r8)

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
    // The plugin runs on the IDE's bundled Kotlin stdlib, not the one it compiles against. Pin the
    // stdlib API to the oldest supported platform (ADR 0003: 2024.2 bundles Kotlin 1.9) so the
    // compiler never emits calls into newer stdlib classes (e.g. Kotlin 2.2's coroutine
    // `SpillingKt`), which fail with NoClassDefFoundError inside older IDEs.
    compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_1_9)
}

tasks.withType<JavaCompile>().configureEach {
    if (name == "compileJava" || name == "compileTestJava") options.release.set(17)
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
