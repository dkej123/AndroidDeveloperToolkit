import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// End-to-end suite (docs/e2e-testing.md): drives the plugin inside a real Android Studio through
// JetBrains Remote-Robot and verifies every effect on a real emulator over adb. It depends on no
// production module on purpose — it only sees what a user sees (UI, device state, log files).
//
// Needs a running environment (e2e/scripts/run-e2e.sh starts one), so the regular `test` task is
// disabled: `./gradlew build`/`check` only compile these tests. Run them with `:e2e:e2eTest`.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

dependencies {
    testImplementation(libs.remote.robot)
    testImplementation(libs.remote.fixtures)
    testImplementation(libs.okhttp)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.test {
    enabled = false
}

tasks.named("check") {
    dependsOn("testClasses")
}

val e2eTest = tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Runs the E2E suite against the Android Studio/emulator started by e2e/scripts (docs/e2e-testing.md)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        // -Pe2e.tags=smoke,perf narrows the run; the default runs everything except `destructive`
        // tests that reboot the device, which need -Pe2e.tags=destructive explicitly.
        val tags = providers.gradleProperty("e2e.tags").orNull
        if (tags != null) includeTags(*tags.split(',').toTypedArray()) else excludeTags("destructive")
    }
    // One IDE and one device are shared: tests must run strictly one at a time.
    maxParallelForks = 1
    // Remote-Robot's Gson (de)serializes Throwable fields of robot-side errors.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED")
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Everything the environment scripts export (e2e/scripts/lib.sh) is forwarded as-is.
    System.getenv().filterKeys { it.startsWith("E2E_") || it == "ANDROID_SDK_ROOT" }
        .forEach { (key, value) -> environment(key, value) }
    systemProperty("e2e.reportDir", layout.buildDirectory.dir("e2e-report").get().asFile.absolutePath)
}
