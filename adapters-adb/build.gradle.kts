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
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}
