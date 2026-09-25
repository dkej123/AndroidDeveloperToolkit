import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Pure Kotlin, KMP-ready per docs/adr/0002. No IntelliJ/Swing/ProcessBuilder/filesystem/JVM-networking
// types belong here — enforced by the root :architectureCheck task.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

// Build with JDK 21 but emit Java 17 bytecode for the IntelliJ Platform 242+ baseline (docs/adr/0003).
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    // The plugin runs on the IDE's bundled Kotlin stdlib, not the one it compiles against. Pin the
    // stdlib API to the oldest supported platform (ADR 0003: 2024.2 bundles Kotlin 1.9) so the
    // compiler never emits calls into newer stdlib classes (e.g. Kotlin 2.2's coroutine
    // `SpillingKt`), which fail with NoClassDefFoundError inside older IDEs.
    compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_1_9)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}
