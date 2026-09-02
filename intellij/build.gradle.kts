import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// IntelliJ Platform frontend and composition root per docs/adr/0001. The only module allowed to
// import IntelliJ/Swing/ToolWindow APIs. Wires :application use cases to :adapters-jvm/:adapters-adb
// implementations at startup (task 007) — not done yet in this bootstrap task.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":application"))
    implementation(project(":adapters-jvm"))
    implementation(project(":adapters-adb"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    intellijPlatform {
        // Compiled against the ADR 0003 baseline: IntelliJ Platform 2024.2+ (build 242+).
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        // org.jetbrains.android (preferred ddmlib/AdbLibService path, docs/adr/0005, when present)
        // is declared `optional` in plugin.xml only. It is bundled with Android Studio but not with
        // IntelliJ IDEA Community, so it is not a compile-time dependency here — task 005 adds a
        // compile-time dependency on it once ddmlib-backed code needs its APIs.
        testFramework(TestFrameworkType.Platform)

        zipSigner()
        // No pluginVerifier() dependency and no pluginVerification {} block: the IntelliJ Plugin
        // Verifier (`verifyPlugin`) is banned from this project's build/CI/local workflow per
        // CLAUDE.md and tasks/001-project-bootstrap-quality.md's Scope — it downloads/unpacks a
        // full IDE distribution and is not required for this task's gates.
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Build with JDK 21 but emit Java 17 bytecode for the IntelliJ Platform 242+ baseline (docs/adr/0003).
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.test {
    useJUnitPlatform()
}
