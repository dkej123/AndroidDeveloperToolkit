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
        // Needed by the bytecode-instrumentation tasks TestFrameworkType.Platform pulls in (nullability
        // instrumentation, forms-runtime), which resolve a Java compiler artifact from this repository.
        intellijDependencies()
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":adapters-jvm"))
    implementation(project(":adapters-adb"))

    // ddmlib device/bridge types (com.android.ddmlib.IDevice, AndroidDebugBridge) referenced
    // directly by the task 007 composition root when wiring the real IDE bridge into
    // DdmlibDeviceSource (docs/adr/0005) — a plain JVM library, not an IntelliJ Platform API.
    // compileOnly, never bundled: see the matching comment in :adapters-adb's build.gradle.kts —
    // the Android plugin dependency above provides the one copy of ddmlib this plugin's runtime
    // ever touches, and that code path only runs when that plugin (and its ddmlib) is present.
    compileOnly(libs.ddmlib)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.ddmlib)
    // junit4: BasePlatformTestCase ultimately extends junit.framework.TestCase (JUnit 3). Every
    // test in this module extends it — a plain Jupiter `@Test` class is not discovered by
    // `:intellij:test`'s IntelliJ-Platform-aware runner (`testFramework(TestFrameworkType.Platform)`
    // below), verified directly rather than assumed; see AdbTransportSelectionTest's class doc.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.platform.launcher)
    // No kotlinx-coroutines-test/-core dependency here: the IntelliJ Platform test sandbox already
    // puts its own bundled kotlinx.coroutines runtime on the classpath, and a second, differently
    // versioned copy pulled in transitively (even test-only) shadows it and breaks IDE-internal
    // calls into coroutines-only APIs (observed as `NoSuchMethodError:
    // BuildersKt.runBlockingWithParallelismCompensation` during indexing, hanging the whole test
    // JVM). Platform tests use plain `runBlocking`/`withContext` from the platform-provided runtime.
    // The vintage engine runs BasePlatformTestCase's JUnit 3/4-style test methods under Gradle's
    // useJUnitPlatform().
    testRuntimeOnly(libs.junit.vintage.engine)

    intellijPlatform {
        // Compiled against the ADR 0003 baseline: IntelliJ Platform 2024.2+ (build 242+).
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        // org.jetbrains.android (preferred ddmlib/AdbLibService path, docs/adr/0005, when present)
        // is declared `optional` in plugin.xml (config-file adb-toolbox-android.xml) and, per task
        // 007, taken as a compile-time-only dependency here so the composition root can call its
        // real device-bridge APIs. Pinned to the exact build (242.26775.15) matching this module's
        // `platformVersion`/pluginVerifier target for guaranteed binary compatibility; it is never
        // bundled into this plugin's distribution (IntelliJ resolves it against the user's own
        // installed Android plugin at runtime), so plain IntelliJ IDEA users without it still run
        // fine via the binary-adb fallback (ADR 0005).
        plugin("org.jetbrains.android", "242.26775.15")

        testFramework(TestFrameworkType.Platform)

        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    // Bytecode instrumentation (@NotNull assertions, .form binding) is irrelevant to this plugin
    // (no Swing UI Designer forms; nullability is enforced by Kotlin's own type system) and its
    // Ant-task wiring is broken against this Gradle Plugin/IDE version combination
    // ("instrumentIdeaExtensions doesn't support the nested ... element"), unrelated to any of
    // this plugin's own code — disabling it avoids depending on an unrelated toolchain bug.
    instrumentCode = false

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
    // Platform tests (BasePlatformTestCase) must never require a real display — this sandboxed
    // build environment has none, and the tests are meant to run deterministically in CI too.
    systemProperty("java.awt.headless", "true")
}

// :domain/:application/:adapters-jvm/:adapters-adb each declare kotlinx-coroutines-core as their
// own `implementation` dependency (correct for building/testing those modules standalone), but the
// sandboxed test IDE this module's platform tests run in already bundles its own (older,
// IntelliJ-patched) copy of that library. Letting our newer copy onto the test sandbox's classpath
// as well lets IDE-internal code bind to the wrong copy and fail with NoSuchMethodError on
// IntelliJ-only coroutines APIs (observed hanging file indexing during a real `:intellij:test`
// run) — excluding it here forces every platform test to run against the one bundled copy, matching
// how a real installed IDE session behaves. kotlin-stdlib is deliberately NOT excluded alongside
// it: this project's pinned Kotlin compiler (2.2.20) emits calls into newer stdlib runtime helpers
// (e.g. `kotlin.coroutines.jvm.internal.SpillingKt`) the sandbox's own older bundled stdlib lacks,
// so our stdlib must stay on the classpath — which in turn means platform tests here must not
// invoke `suspend` functions (AdbTransportSelectionTest verifies routing structurally instead of
// calling through `executeText`, for exactly this reason).
configurations.matching { it.name == "testRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}
