import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}
