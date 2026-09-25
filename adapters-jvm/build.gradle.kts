import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// JVM-only per docs/adr/0001: implements :domain ports needing the JVM (centralized process
// execution, filesystem, host LAN IP discovery). Depends on :domain only. No IntelliJ/Swing types.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
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
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}
