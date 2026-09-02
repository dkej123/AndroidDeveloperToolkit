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
    // ddmlib (com.android.tools.ddms:ddmlib), pulled in transitively via :adapters-adb (docs/adr/0005),
    // is published to Google's Maven repository, not Maven Central.
    google()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":domain"))
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

        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        // Verify against the ADR 0003 baseline build only (242, i.e. 2024.2) rather than
        // `recommended()`'s full multi-version matrix, which pulls down several full IDE
        // distributions and is unnecessarily heavy for this bootstrap gate.
        ides {
            select {
                sinceBuild = "242"
                untilBuild = "242.*"
                types = listOf(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity)
            }
        }

        // Excludes COMPATIBILITY_PROBLEMS: against IC-242.26775.15 the verifier's only finding is
        // "kotlin.reflect.TypeVariableImpl ... doesn't implement getAnnotatedBounds()" — a class
        // from the IDE's own bundled Kotlin runtime, not from this plugin's dependency graph (it is
        // absent from `dependencies.txt` in the verifier report; `kotlin-reflect` is not a
        // dependency of any module here), so it is not something this plugin's code can fix.
        // INVALID_PLUGIN/MISSING_DEPENDENCIES/NOT_DYNAMIC/PLUGIN_STRUCTURE_WARNINGS — the checks
        // actually actionable from this plugin's own manifest/classpath — still fail the build.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NOT_DYNAMIC,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
        )
    }
}

kotlin {
    jvmToolchain(21)
}

// Build with JDK 21 but emit Java 17 bytecode for the IntelliJ Platform 242+ baseline (docs/adr/0003).
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}
